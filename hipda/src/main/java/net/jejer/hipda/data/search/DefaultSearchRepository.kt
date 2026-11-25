package net.jejer.hipda.data.search

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.toGbkSafe
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.search.SearchParser
import net.jejer.hipda.parser.simplelist.SimpleListParser

@Singleton
class DefaultSearchRepository @Inject constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val searchHistoryStore: SearchHistoryStore,
) : SearchRepository {

    override val searchHistory: Flow<List<String>>
        get() = searchHistoryStore.history

    override suspend fun search(
        query: String,
        author: String,
        forumId: Int?,
        fullText: Boolean,
        page: Int,
        searchId: String?,
        priority: AccessPriority,
        recordHistory: Boolean,
    ): SearchResult {
        if (page < 1) {
            return SearchError.InvalidInput("页码必须大于等于 1")
        }
        if (forumId != null && forumId <= 0) {
            return SearchError.InvalidInput("版块 ID 无效")
        }
        val normalizedSearchId = if (searchId.isNullOrBlank()) {
            null
        } else {
            validateSearchId(searchId)
                ?: return SearchError.InvalidInput("搜索标识无效")
        }

        val trimmedQuery = query.trim()
        val trimmedAuthor = author.trim()
        if (normalizedSearchId == null && trimmedQuery.isEmpty() && trimmedAuthor.isEmpty()) {
            return SearchError.InvalidInput("请输入搜索关键词或作者")
        }

        val relativePath = buildSearchPath(
            query = trimmedQuery,
            author = trimmedAuthor,
            forumId = forumId,
            fullText = fullText,
            page = page,
            searchId = normalizedSearchId,
        )

        val result = executeSearchRequest(
            relativePath = relativePath,
            page = page,
            fullText = fullText,
            priority = priority,
            defaultErrorMessage = "搜索失败",
        )

        if (result is SearchResult.Success &&
            recordHistory &&
            page == 1 &&
            normalizedSearchId == null &&
            trimmedQuery.isNotEmpty()
        ) {
            try {
                searchHistoryStore.record(trimmedQuery)
            } catch (_: IOException) {
                // Best-effort local history persistence: never fail a completed search on disk I/O error.
            }
        }
        return result
    }

    override suspend fun getNewPosts(
        page: Int,
        searchId: String?,
        priority: AccessPriority,
    ): SearchResult {
        if (page < 1) {
            return SearchError.InvalidInput("页码必须大于等于 1")
        }
        val normalizedSearchId = if (searchId.isNullOrBlank()) {
            null
        } else {
            validateSearchId(searchId)
                ?: return SearchError.InvalidInput("搜索标识无效")
        }

        val relativePath = buildNewPostsPath(
            page = page,
            searchId = normalizedSearchId,
        )

        return executeSearchRequest(
            relativePath = relativePath,
            page = page,
            fullText = false,
            priority = priority,
            defaultErrorMessage = "加载今日新帖失败",
        )
    }

    override suspend fun getMyThreads(
        page: Int,
        priority: AccessPriority,
    ): MyThreadsResult {
        if (page < 1) {
            return SearchError.InvalidInput("页码必须大于等于 1")
        }
        val response = try {
            forumHttpClient.getHtml(
                relativePath = "$MY_THREADS_PATH_PREFIX$page",
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return mapSearchIoException(e)
        }

        val parsed = SimpleListParser.parseMyThreads(response.html)
        val siteError = parsed.meta.errorMessage
        if (siteError != null) {
            val trimmedError = siteError.trim()
            if (!isEmptySearchMessage(trimmedError)) {
                return SearchError.SiteError(trimmedError.ifEmpty { "加载我的帖子失败" })
            }
            return MyThreadsResult.Success(
                page = page,
                threadsPage = parsed.copy(items = emptyList()),
            )
        }

        if (!parsed.hasTable && !hasEmptyListMarker(response.html)) {
            return SearchError.InvalidPage("我的帖子页面解析失败")
        }

        return MyThreadsResult.Success(
            page = page,
            threadsPage = parsed,
        )
    }

    override suspend fun getMyReplies(
        page: Int,
        priority: AccessPriority,
    ): MyRepliesResult {
        if (page < 1) {
            return SearchError.InvalidInput("页码必须大于等于 1")
        }
        val response = try {
            forumHttpClient.getHtml(
                relativePath = "$MY_REPLIES_PATH_PREFIX$page",
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return mapSearchIoException(e)
        }

        val parsed = SimpleListParser.parseMyReplies(response.html)
        val siteError = parsed.meta.errorMessage
        if (siteError != null) {
            val trimmedError = siteError.trim()
            if (!isEmptySearchMessage(trimmedError)) {
                return SearchError.SiteError(trimmedError.ifEmpty { "加载我的回复失败" })
            }
            return MyRepliesResult.Success(
                page = page,
                repliesPage = parsed.copy(items = emptyList()),
            )
        }

        if (!parsed.hasTable && !hasEmptyListMarker(response.html)) {
            return SearchError.InvalidPage("我的回复页面解析失败")
        }

        return MyRepliesResult.Success(
            page = page,
            repliesPage = parsed,
        )
    }

    override suspend fun recordSearchHistory(query: String): List<String> =
        searchHistoryStore.record(query)

    override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? =
        searchHistoryStore.remove(query)

    override suspend fun restoreSearchHistory(query: String, index: Int): List<String> =
        searchHistoryStore.restore(query, index)

    override suspend fun clearSearchHistory() {
        searchHistoryStore.clearAll()
    }

    override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? {
        val forumServer = settingsRepository.settings.first().forumServer
        return ForumLinkResolver.resolve(rawInput = rawInput, forumServer = forumServer)
    }

    private suspend fun executeSearchRequest(
        relativePath: String,
        page: Int,
        fullText: Boolean,
        priority: AccessPriority,
        defaultErrorMessage: String,
    ): SearchResult {
        val forumServer = settingsRepository.settings.first().forumServer
        val requireAuth = authRepository.currentAccount.value != null
        val response = try {
            forumHttpClient.getHtml(
                relativePath = relativePath,
                priority = priority,
                requireAuth = requireAuth,
            )
        } catch (e: IOException) {
            return mapSearchIoException(e)
        }

        val avatarBaseUrl = deriveAvatarBaseUrl(forumServer).ifEmpty { null }
        val parsedPage: SearchPage = SearchParser.parse(
            html = response.html,
            fullText = fullText,
            avatarBaseUrl = avatarBaseUrl,
        )

        if (parsedPage.items.isNotEmpty()) {
            return SearchResult.Success(
                page = page,
                fullText = fullText,
                searchPage = parsedPage,
            )
        }

        val siteError = parsedPage.meta.errorMessage
        if (siteError != null) {
            val trimmedError = siteError.trim()
            if (isEmptySearchMessage(trimmedError)) {
                return SearchResult.Success(
                    page = page,
                    fullText = fullText,
                    searchPage = parsedPage.copy(items = emptyList()),
                )
            }
            if (!parsedPage.meta.session.isLoggedIn && isGuestAuthRequiredMessage(trimmedError)) {
                return SearchError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = trimmedError.ifEmpty { "需要登录后查看" },
                )
            }
            return SearchError.SiteError(trimmedError.ifEmpty { defaultErrorMessage })
        }

        if (!parsedPage.meta.session.isLoggedIn && isLoginRedirectOrFormPage(response)) {
            return SearchError.AuthRequired(
                reason = AuthRequiredReason.NOT_LOGGED_IN,
                message = "需要登录后查看",
            )
        }

        if (!hasSearchPageStructure(response.html)) {
            return SearchError.InvalidPage("搜索结果页面解析失败")
        }

        return SearchResult.Success(
            page = page,
            fullText = fullText,
            searchPage = parsedPage,
        )
    }

    internal companion object {
        private const val NEW_POSTS_PATH = "search.php?srchfrom=86400&searchsubmit=yes"
        private const val SEARCH_BY_ID_PREFIX = "search.php?searchid="
        private const val SEARCH_BY_ID_SUFFIX = "&orderby=lastpost&ascdesc=desc&searchsubmit=yes"
        private const val MY_THREADS_PATH_PREFIX = "my.php?item=threads&page="
        private const val MY_REPLIES_PATH_PREFIX = "my.php?item=posts&page="
        private const val AVATAR_PATH = "uc_server/data/avatar/"
        private const val FORUM_ALL = "all"

        private val EMPTY_SEARCH_MARKERS = listOf(
            "没有找到匹配结果",
            "没有找到相关",
            "没有找到任何",
            "对不起，没有找到",
            "未找到匹配",
            "未找到相关",
            "暂无数据",
            "没有任何记录",
            "没有相关帖子",
            "没有发过帖",
        )

        private val GUEST_AUTH_REQUIRED_MARKERS = listOf(
            "登录",
            "登陆",
            "无权",
            "没有权限",
            "权限",
            "游客",
        )

        internal fun validateSearchId(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val valid = trimmed.all { ch ->
                ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == '-'
            }
            return if (valid) trimmed else null
        }

        internal fun encodeGbkQueryParam(raw: String): String {
            if (raw.isEmpty()) return ""
            return URLEncoder.encode(toGbkSafe(raw), "GBK")
        }

        internal fun buildSearchPath(
            query: String,
            author: String,
            forumId: Int?,
            fullText: Boolean,
            page: Int,
            searchId: String?,
        ): String {
            val base = if (searchId != null) {
                "$SEARCH_BY_ID_PREFIX$searchId$SEARCH_BY_ID_SUFFIX"
            } else {
                val srchType = if (fullText) "fulltext" else "title"
                val encodedQuery = encodeGbkQueryParam(query)
                val encodedAuthor = encodeGbkQueryParam(author)
                val fidParam = forumId?.toString() ?: FORUM_ALL
                "search.php?srchtype=$srchType&srchtxt=$encodedQuery&searchsubmit=true&st=on" +
                    "&srchuname=$encodedAuthor&srchfilter=all&srchfrom=0&before=" +
                    "&orderby=lastpost&ascdesc=desc&srchfid%5B0%5D=$fidParam"
            }
            return if (page > 1) "$base&page=$page" else base
        }

        internal fun buildNewPostsPath(page: Int, searchId: String?): String {
            val base = if (searchId != null) {
                "$SEARCH_BY_ID_PREFIX$searchId$SEARCH_BY_ID_SUFFIX"
            } else {
                NEW_POSTS_PATH
            }
            return if (page > 1) "$base&page=$page" else base
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

        internal fun isEmptySearchMessage(message: String): Boolean =
            message.isNotEmpty() && EMPTY_SEARCH_MARKERS.any { marker -> message.contains(marker) }

        private fun isGuestAuthRequiredMessage(message: String): Boolean =
            message.isNotEmpty() && GUEST_AUTH_REQUIRED_MARKERS.any { marker -> message.contains(marker) }

        private fun isLoginRedirectOrFormPage(response: ForumHtmlResponse): Boolean {
            if (response.url.contains("logging.php", ignoreCase = true)) return true
            val html = response.html
            return html.contains("name=\"login\"", ignoreCase = true) ||
                html.contains("id=\"loginform\"", ignoreCase = true) ||
                html.contains("action=\"logging.php?action=login", ignoreCase = true)
        }

        private fun hasEmptyListMarker(html: String): Boolean =
            html.contains("nodata", ignoreCase = true) || html.contains("暂无数据")

        private fun hasSearchPageStructure(html: String): Boolean =
            html.contains("datatable", ignoreCase = true) ||
                html.contains("searchlist", ignoreCase = true) ||
                html.contains("searchform", ignoreCase = true) ||
                html.contains("alert_info", ignoreCase = true) ||
                html.contains("nodata", ignoreCase = true) ||
                html.contains("暂无数据") ||
                html.contains("找到相关主题") ||
                html.contains("没有找到")
    }
}
