package net.jejer.hipda.data.user

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.search.SearchParser
import net.jejer.hipda.parser.user.UserParser

@Singleton
class DefaultUserRepository @Inject constructor(
    private val forumHttpClient: ForumHttpClient,
    private val settingsRepository: SettingsRepository,
) : UserRepository {

    override suspend fun getUserProfile(
        uid: String,
        priority: AccessPriority,
    ): UserProfileResult {
        val normalizedUid = validateUid(uid)
            ?: return UserError.InvalidInput("用户 ID 无效")

        val response = try {
            forumHttpClient.getHtml(
                relativePath = "$USER_INFO_PATH_PREFIX$normalizedUid",
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return mapUserIoException(e)
        }

        val parsed = UserParser.parseUserInfo(response.html)
        val siteError = parsed.meta.errorMessage
        if (siteError != null) {
            return UserError.SiteError(siteError.trim().ifEmpty { "获取用户资料失败" })
        }

        if (parsed.uid.isNullOrBlank() || parsed.username.isNullOrBlank()) {
            return UserError.InvalidPage("用户资料页面解析失败")
        }

        val resolved = if (parsed.formhash.isNullOrBlank() && !parsed.meta.formhash.isNullOrBlank()) {
            parsed.copy(formhash = parsed.meta.formhash)
        } else {
            parsed
        }
        return UserProfileResult.Success(profile = resolved)
    }

    override suspend fun getUserThreads(
        uid: String,
        page: Int,
        searchId: String?,
        priority: AccessPriority,
    ): UserThreadsResult {
        val normalizedUid = validateUid(uid)
            ?: return UserError.InvalidInput("用户 ID 无效")
        if (page < 1) {
            return UserError.InvalidInput("页码必须大于等于 1")
        }
        val normalizedSearchId = if (searchId.isNullOrBlank()) {
            null
        } else {
            validateSearchId(searchId)
                ?: return UserError.InvalidInput("搜索标识无效")
        }

        val relativePath = buildUserThreadsPath(
            uid = normalizedUid,
            page = page,
            searchId = normalizedSearchId,
        )

        val forumServer = settingsRepository.settings.first().forumServer
        val response = try {
            forumHttpClient.getHtml(
                relativePath = relativePath,
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return mapUserIoException(e)
        }

        val avatarBaseUrl = deriveAvatarBaseUrl(forumServer)
        val parsedPage = SearchParser.parseTitleSearch(
            html = response.html,
            avatarBaseUrl = avatarBaseUrl,
        )

        if (parsedPage.items.isNotEmpty()) {
            return UserThreadsResult.Success(
                uid = normalizedUid,
                page = page,
                searchPage = parsedPage,
            )
        }

        val siteError = parsedPage.meta.errorMessage
        if (siteError != null) {
            val trimmedError = siteError.trim()
            if (!isEmptySearchMessage(trimmedError)) {
                return UserError.SiteError(trimmedError.ifEmpty { "获取用户帖子失败" })
            }
        }

        return UserThreadsResult.Success(
            uid = normalizedUid,
            page = page,
            searchPage = parsedPage,
        )
    }

    internal companion object {
        private const val USER_INFO_PATH_PREFIX = "space.php?uid="
        private const val SEARCH_USER_THREADS_PREFIX =
            "search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid="
        private const val SEARCH_BY_ID_PREFIX = "search.php?searchid="
        private const val SEARCH_BY_ID_SUFFIX = "&orderby=lastpost&ascdesc=desc&searchsubmit=yes"
        private const val AVATAR_PATH = "uc_server/data/avatar/"

        private val EMPTY_SEARCH_MARKERS = listOf(
            "没有找到匹配结果",
            "没有找到相关",
            "未找到匹配",
            "未找到相关",
            "暂无数据",
            "没有任何记录",
            "没有相关帖子",
            "没有发过帖",
        )

        internal fun validateUid(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
            val numeric = trimmed.toLongOrNull() ?: return null
            return if (numeric > 0L) trimmed else null
        }

        internal fun validateSearchId(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val valid = trimmed.all { ch ->
                ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == '-'
            }
            return if (valid) trimmed else null
        }

        internal fun buildUserThreadsPath(uid: String, page: Int, searchId: String?): String {
            val base = if (searchId == null) {
                "$SEARCH_USER_THREADS_PREFIX$uid"
            } else {
                "$SEARCH_BY_ID_PREFIX$searchId$SEARCH_BY_ID_SUFFIX"
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
    }
}
