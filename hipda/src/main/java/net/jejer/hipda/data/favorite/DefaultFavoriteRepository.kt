package net.jejer.hipda.data.favorite

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
import net.jejer.hipda.parser.post.FavoriteParser
import net.jejer.hipda.parser.simplelist.SimpleListParser

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultFavoriteRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val favoriteCacheStore: FavoriteCacheStore,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) : FavoriteRepository {

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        authRepository: AuthRepository,
        favoriteCacheStore: FavoriteCacheStore,
    ) : this(
        forumHttpClient = forumHttpClient,
        authRepository = authRepository,
        favoriteCacheStore = favoriteCacheStore,
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val accountReadVersions = HashMap<String, Long>()
    private val kindMutationVersions = HashMap<Pair<String, FavoriteKind>, Long>()

    private val _activeSnapshot = MutableStateFlow(FavoriteCacheSnapshot())
    private val _favoriteTids = MutableStateFlow<Set<String>>(emptySet())
    private val _attentionTids = MutableStateFlow<Set<String>>(emptySet())

    override val favoriteTids: StateFlow<Set<String>> = _favoriteTids.asStateFlow()
    override val attentionTids: StateFlow<Set<String>> = _attentionTids.asStateFlow()

    init {
        scope.launch {
            authRepository.currentAccount
                .map { it?.uid?.trim()?.takeIf(String::isNotEmpty) }
                .distinctUntilChanged()
                .collectLatest { uid ->
                    if (uid == null) {
                        synchronized(stateLock) {
                            publishSnapshotLocked(FavoriteCacheSnapshot())
                        }
                    } else {
                        synchronized(stateLock) {
                            if (_activeSnapshot.value.ownerUid != uid) {
                                bumpAccountReadVersionLocked(uid)
                                publishSnapshotLocked(FavoriteCacheSnapshot(ownerUid = uid))
                            }
                        }
                        favoriteCacheStore.observe(uid)
                            .conflate()
                            .collect {
                                val versionBeforeRead = synchronized(stateLock) {
                                    accountReadVersionLocked(uid)
                                }
                                val latest = favoriteCacheStore.read(uid)
                                synchronized(stateLock) {
                                    if (accountReadVersionLocked(uid) == versionBeforeRead &&
                                        currentActiveUid() == uid
                                    ) {
                                        publishSnapshotLocked(latest)
                                    }
                                }
                            }
                    }
                }
        }
    }

    override fun isFavorite(tid: String): Boolean = isCached(FavoriteKind.FAVORITE, tid)

    override fun isAttention(tid: String): Boolean = isCached(FavoriteKind.ATTENTION, tid)

    private fun isCached(kind: FavoriteKind, tid: String): Boolean {
        val normalizedTid = normalizeFavoriteTid(tid) ?: return false
        val activeUid = currentActiveUid() ?: return false
        val snapshot = _activeSnapshot.value
        if (snapshot.ownerUid != activeUid) return false
        return normalizedTid in snapshot.tidsFor(kind)
    }

    override suspend fun getList(
        kind: FavoriteKind,
        page: Int,
        priority: AccessPriority,
    ): FavoriteListResult {
        if (page < 1) {
            return FavoriteError.InvalidInput("页码必须大于等于 1")
        }

        val accountUidBefore = currentActiveUid()
        val mutationVersionBefore = synchronized(stateLock) {
            accountUidBefore?.let { kindMutationVersionLocked(it, kind) }
        }

        val response = try {
            forumHttpClient.getHtml(
                relativePath = buildListPath(kind, page),
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return mapFavoriteIoException(e)
        }

        val targetUid = when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
            is SessionOwnership.Valid -> ownership.uid
            is SessionOwnership.Invalid -> return ownership.error
        }

        val parsed = SimpleListParser.parseFavoritesList(response.html)
        val siteError = parsed.meta.errorMessage
        if (siteError != null) {
            return FavoriteError.SiteError(siteError.trim().ifEmpty { "加载列表失败" })
        }

        if (parsed.items.isEmpty() && !hasFavoritesPageStructure(response.html)) {
            return FavoriteError.InvalidPage("收藏列表页面解析失败")
        }

        val validPageTids = parsed.items.mapNotNull { normalizeFavoriteTid(it.tid) }
        if (validPageTids.isNotEmpty()) {
            operationMutex.withLock {
                val shouldMerge = synchronized(stateLock) {
                    currentActiveUid() == targetUid &&
                        (mutationVersionBefore == null ||
                            kindMutationVersionLocked(targetUid, kind) == mutationVersionBefore)
                }
                if (shouldMerge) {
                    val updated = try {
                        favoriteCacheStore.merge(
                            ownerUid = targetUid,
                            kind = kind,
                            tids = validPageTids,
                        )
                    } catch (_: IOException) {
                        // Best-effort cache merge on list load: never fail a completed list fetch on disk I/O error.
                        null
                    }
                    if (updated != null) {
                        synchronized(stateLock) {
                            if (currentActiveUid() == targetUid) {
                                bumpAccountReadVersionLocked(targetUid)
                                publishSnapshotLocked(updated)
                            }
                        }
                    }
                }
            }
        }

        return FavoriteListResult.Success(
            kind = kind,
            page = page,
            listPage = parsed,
        )
    }

    override suspend fun syncCache(
        kind: FavoriteKind,
        maxPages: Int,
        priority: AccessPriority,
    ): FavoriteSyncResult {
        if (maxPages < 1) {
            return FavoriteError.InvalidInput("同步页数上限必须大于等于 1")
        }

        val accountUidBefore = currentActiveUid()
            ?: return FavoriteError.AuthRequired(
                reason = AuthRequiredReason.NOT_LOGGED_IN,
                message = "当前未登录",
            )
        val mutationVersionBefore = synchronized(stateLock) {
            kindMutationVersionLocked(accountUidBefore, kind)
        }

        val collectedTids = LinkedHashSet<String>()
        var pagesSynced = 0
        var lastPage = 1

        for (page in 1..maxPages) {
            val response = try {
                forumHttpClient.getHtml(
                    relativePath = buildListPath(kind, page),
                    priority = priority,
                    requireAuth = true,
                )
            } catch (e: IOException) {
                return mapFavoriteIoException(e)
            }

            when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
                is SessionOwnership.Valid -> Unit
                is SessionOwnership.Invalid -> return ownership.error
            }

            val siteError = response.meta.errorMessage
            if (siteError != null) {
                return FavoriteError.SiteError(siteError.trim().ifEmpty { "同步收藏缓存失败" })
            }

            val parsed = FavoriteParser.parseFavoriteIdsPage(response.html)
            if (parsed.tids.isEmpty() && !hasFavoritesPageStructure(response.html)) {
                return FavoriteError.InvalidPage("收藏列表页面解析失败")
            }

            pagesSynced++
            lastPage = parsed.lastPage
            if (parsed.tids.isEmpty()) {
                break
            }
            collectedTids.addAll(parsed.tids)
            if (page >= parsed.lastPage) {
                break
            }
        }

        return operationMutex.withLock {
            if (currentActiveUid() != accountUidBefore) {
                return@withLock FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "账号已切换，请重试",
                )
            }

            val syncedAtMs = clock()
            val hasConcurrentMutation = synchronized(stateLock) {
                kindMutationVersionLocked(accountUidBefore, kind) != mutationVersionBefore
            }
            if (hasConcurrentMutation) {
                val currentTids = synchronized(stateLock) {
                    _activeSnapshot.value.tidsFor(kind)
                }
                return@withLock FavoriteSyncResult.Success(
                    kind = kind,
                    tids = currentTids,
                    pagesSynced = pagesSynced,
                    lastPage = lastPage,
                    syncedAtMs = syncedAtMs,
                )
            }

            val updated = try {
                favoriteCacheStore.replace(
                    ownerUid = accountUidBefore,
                    kind = kind,
                    tids = collectedTids,
                    syncedAtMs = syncedAtMs,
                )
            } catch (e: IOException) {
                return@withLock FavoriteError.StorageError(
                    message = e.message ?: "本地收藏缓存写入失败",
                    cause = e,
                )
            }
            synchronized(stateLock) {
                if (currentActiveUid() == accountUidBefore) {
                    bumpKindMutationVersionLocked(accountUidBefore, kind)
                    bumpAccountReadVersionLocked(accountUidBefore)
                    publishSnapshotLocked(updated)
                }
            }

            FavoriteSyncResult.Success(
                kind = kind,
                tids = updated.tidsFor(kind),
                pagesSynced = pagesSynced,
                lastPage = lastPage,
                syncedAtMs = syncedAtMs,
            )
        }
    }

    override suspend fun add(
        kind: FavoriteKind,
        tid: String,
        priority: AccessPriority,
    ): FavoriteMutationResult = mutateViaAjax(
        kind = kind,
        rawTid = tid,
        isAdd = true,
        priority = priority,
    )

    override suspend fun remove(
        kind: FavoriteKind,
        tid: String,
        priority: AccessPriority,
    ): FavoriteMutationResult = mutateViaAjax(
        kind = kind,
        rawTid = tid,
        isAdd = false,
        priority = priority,
    )

    override suspend fun removeByForm(
        kind: FavoriteKind,
        tid: String,
        formhash: String,
        priority: AccessPriority,
    ): FavoriteMutationResult {
        val normalizedTid = normalizeFavoriteTid(tid)
            ?: return FavoriteError.InvalidInput("帖子 ID 无效")
        val trimmedFormhash = formhash.trim()
        if (trimmedFormhash.isEmpty()) {
            return FavoriteError.InvalidInput("缺少操作令牌 formhash")
        }

        return operationMutex.withLock {
            val accountUidBefore = currentActiveUid()
                ?: return@withLock FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "当前未登录",
                )

            val response = try {
                forumHttpClient.postGbkForm(
                    relativePath = buildListPath(kind, page = 1),
                    fields = listOf(
                        kind.formSubmitField to "true",
                        FIELD_DELETE to normalizedTid,
                        FIELD_FORMHASH to trimmedFormhash,
                    ),
                    priority = priority,
                    requireAuth = true,
                )
            } catch (e: IOException) {
                return@withLock mapFavoriteIoException(e)
            }

            val targetUid = when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
                is SessionOwnership.Valid -> ownership.uid
                is SessionOwnership.Invalid -> return@withLock ownership.error
            }

            val siteError = response.meta.errorMessage
            if (siteError != null) {
                return@withLock FavoriteError.SiteError(siteError.trim().ifEmpty { "移除失败" })
            }

            val updated = try {
                favoriteCacheStore.remove(
                    ownerUid = targetUid,
                    kind = kind,
                    tid = normalizedTid,
                )
            } catch (e: IOException) {
                return@withLock FavoriteError.StorageError(
                    message = e.message ?: "本地收藏缓存写入失败",
                    cause = e,
                )
            }
            synchronized(stateLock) {
                if (currentActiveUid() == targetUid) {
                    bumpKindMutationVersionLocked(targetUid, kind)
                    bumpAccountReadVersionLocked(targetUid)
                    publishSnapshotLocked(updated)
                }
            }

            val defaultMessage = if (kind == FavoriteKind.FAVORITE) "已取消收藏" else "已取消关注"
            FavoriteMutationResult.Success(
                kind = kind,
                tid = normalizedTid,
                message = defaultMessage,
                alreadyApplied = false,
            )
        }
    }

    private suspend fun mutateViaAjax(
        kind: FavoriteKind,
        rawTid: String,
        isAdd: Boolean,
        priority: AccessPriority,
    ): FavoriteMutationResult {
        val normalizedTid = normalizeFavoriteTid(rawTid)
            ?: return FavoriteError.InvalidInput("帖子 ID 无效")

        return operationMutex.withLock {
            val accountUidBefore = currentActiveUid()
                ?: return@withLock FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "当前未登录",
                )

            val response = try {
                forumHttpClient.getHtml(
                    relativePath = buildAjaxActionPath(kind = kind, tid = normalizedTid, isAdd = isAdd),
                    priority = priority,
                    requireAuth = true,
                    isUnauthenticated = ::isFavoriteAjaxUnauthenticated,
                )
            } catch (e: IOException) {
                return@withLock mapFavoriteIoException(e)
            }

            val targetUid = when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
                is SessionOwnership.Valid -> ownership.uid
                is SessionOwnership.Invalid -> return@withLock ownership.error
            }

            val message = FavoriteParser.parseFavoriteActionXml(response.html).message.trim()
            if (message.isEmpty()) {
                val fallbackError = response.meta.errorMessage?.trim().orEmpty()
                return@withLock if (fallbackError.isNotEmpty()) {
                    FavoriteError.SiteError(fallbackError)
                } else {
                    FavoriteError.InvalidPage("收藏操作响应解析失败")
                }
            }

            val outcome = classifyAjaxOutcome(message = message, isAdd = isAdd)
                ?: return@withLock FavoriteError.SiteError(message)

            val updated = try {
                if (isAdd) {
                    favoriteCacheStore.add(ownerUid = targetUid, kind = kind, tid = normalizedTid)
                } else {
                    favoriteCacheStore.remove(ownerUid = targetUid, kind = kind, tid = normalizedTid)
                }
            } catch (e: IOException) {
                return@withLock FavoriteError.StorageError(
                    message = e.message ?: "本地收藏缓存写入失败",
                    cause = e,
                )
            }
            synchronized(stateLock) {
                if (currentActiveUid() == targetUid) {
                    bumpKindMutationVersionLocked(targetUid, kind)
                    bumpAccountReadVersionLocked(targetUid)
                    publishSnapshotLocked(updated)
                }
            }

            FavoriteMutationResult.Success(
                kind = kind,
                tid = normalizedTid,
                message = message,
                alreadyApplied = outcome == AjaxOutcome.ALREADY_APPLIED,
            )
        }
    }

    private fun verifySessionOwnership(
        accountUidBefore: String?,
        response: ForumHtmlResponse,
    ): SessionOwnership {
        val activeUidAfter = currentActiveUid()
            ?: return SessionOwnership.Invalid(
                FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "当前未登录",
                ),
            )
        if (accountUidBefore != null && activeUidAfter != accountUidBefore) {
            return SessionOwnership.Invalid(
                FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "账号已切换，请重试",
                ),
            )
        }
        val responseUid = response.meta.session.uid?.trim()?.takeIf { it.isNotEmpty() }
        if (responseUid != null && responseUid != activeUidAfter) {
            return SessionOwnership.Invalid(
                FavoriteError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "响应会话与当前账号不一致",
                ),
            )
        }
        return SessionOwnership.Valid(activeUidAfter)
    }

    private fun accountReadVersionLocked(uid: String): Long =
        accountReadVersions[uid] ?: 0L

    private fun bumpAccountReadVersionLocked(uid: String): Long {
        val next = (accountReadVersions[uid] ?: 0L) + 1L
        accountReadVersions[uid] = next
        return next
    }

    private fun kindMutationVersionLocked(uid: String, kind: FavoriteKind): Long =
        kindMutationVersions[uid to kind] ?: 0L

    private fun bumpKindMutationVersionLocked(uid: String, kind: FavoriteKind): Long {
        val key = uid to kind
        val next = (kindMutationVersions[key] ?: 0L) + 1L
        kindMutationVersions[key] = next
        return next
    }

    private fun currentActiveUid(): String? =
        authRepository.currentAccount.value?.uid?.trim()?.takeIf { it.isNotEmpty() }

    private fun publishSnapshotLocked(snapshot: FavoriteCacheSnapshot) {
        _activeSnapshot.value = snapshot
        _favoriteTids.value = snapshot.favoriteTids
        _attentionTids.value = snapshot.attentionTids
    }

    private enum class AjaxOutcome {
        APPLIED,
        ALREADY_APPLIED,
    }

    private sealed interface SessionOwnership {
        data class Valid(val uid: String) : SessionOwnership
        data class Invalid(val error: FavoriteError) : SessionOwnership
    }

    internal companion object {
        private const val FIELD_DELETE = "delete[]"
        private const val FIELD_FORMHASH = "formhash"

        private val UNAUTH_AJAX_MARKERS: List<String> = listOf(
            "您还未登录",
            "您尚未登录",
            "尚未登录",
            "请先登录",
            "您需要先登录",
            "您无权进行当前操作",
            "无权进行当前操作",
            "游客无法",
        )

        private val HARD_FAILURE_MARKERS: List<String> = listOf(
            "失败",
            "无法",
            "不能",
            "已满",
            "上限",
            "不存在",
            "无效",
            "错误",
            "无权",
            "没有权限",
            "未定义",
            "不正确",
            "禁止",
            "超限",
            "超出",
            "被锁定",
            "已关闭",
            "参数有误",
        )

        private val APOLOGY_FAILURE_MARKERS: List<String> = listOf(
            "对不起",
            "抱歉",
        )

        private val ALREADY_ADDED_MARKERS: List<String> = listOf(
            "曾经收藏",
            "曾经关注",
            "已经收藏",
            "已经关注",
            "正在收藏",
            "正在关注",
            "已收藏过",
            "已关注过",
            "已在您的收藏",
            "已在您的关注",
        )

        private val ALREADY_REMOVED_MARKERS: List<String> = listOf(
            "没有收藏",
            "未收藏",
            "不在收藏",
            "没有关注",
            "未关注",
            "不在关注",
        )

        internal fun buildListPath(kind: FavoriteKind, page: Int): String {
            val base = "my.php?item=${kind.itemParam}&type=thread"
            return if (page > 1) "$base&page=$page" else base
        }

        internal fun buildAjaxActionPath(
            kind: FavoriteKind,
            tid: String,
            isAdd: Boolean,
        ): String {
            val action = if (isAdd) "add" else "remove"
            return "my.php?item=${kind.itemParam}&action=$action&inajax=1&ajaxtarget=favorite_msg&tid=$tid"
        }

        /**
         * Custom `isUnauthenticated` predicate for `my.php?...&inajax=1` XML responses.
         *
         * Discuz! AJAX XML replies contain no `#umenu`, so the default predicate would falsely
         * classify every normal reply as logged out. This predicate returns `true` only when the
         * XML explicitly indicates an unauthenticated session or when an unauthenticated HTML
         * fallback / login page is returned.
         */
        internal fun isFavoriteAjaxUnauthenticated(response: ForumHtmlResponse): Boolean {
            val trimmedHtml = response.html.trimStart()
            val isXml = trimmedHtml.startsWith("<?xml", ignoreCase = true) ||
                trimmedHtml.startsWith("<root", ignoreCase = true)
            if (!isXml) {
                if (response.url.contains("logging.php", ignoreCase = true) ||
                    trimmedHtml.contains("id=\"loginform\"", ignoreCase = true) ||
                    (trimmedHtml.contains("id=\"umenu\"") && !response.meta.session.isLoggedIn)
                ) {
                    return true
                }
            }
            val parsedMessage = FavoriteParser.parseFavoriteActionXml(response.html).message.trim()
            val textToCheck = parsedMessage.ifEmpty {
                if (!isXml) response.meta.errorMessage?.trim().orEmpty() else ""
            }
            if (textToCheck.isEmpty()) return false
            return UNAUTH_AJAX_MARKERS.any { marker -> textToCheck.contains(marker) }
        }

        /**
         * Classifies a non-empty `<root>` AJAX XML response aligned with legacy `FavoriteHelper.java`:
         * instead of enumerating a fragile positive phrase allowlist that breaks on Discuz! 7.2
         * wording variations (e.g. `此主题已成功从您的收藏夹中移除。`, `指定主题已成功加入到关注列表中`,
         * `您正在关注这个主题`, `已取消对此主题的关注。`), any non-empty `<root>` response that is not
         * an unauthenticated error, hard failure, or apology error is accepted as applied (with
         * idempotent already-added/already-removed messages flagged as [AjaxOutcome.ALREADY_APPLIED]).
         */
        private fun classifyAjaxOutcome(message: String, isAdd: Boolean): AjaxOutcome? {
            if (HARD_FAILURE_MARKERS.any { marker -> message.contains(marker) }) {
                return null
            }
            if (isAdd) {
                if (ALREADY_ADDED_MARKERS.any { message.contains(it) } &&
                    ALREADY_REMOVED_MARKERS.none { message.contains(it) }
                ) {
                    return AjaxOutcome.ALREADY_APPLIED
                }
            } else {
                if (ALREADY_REMOVED_MARKERS.any { message.contains(it) }) {
                    return AjaxOutcome.ALREADY_APPLIED
                }
            }
            if (APOLOGY_FAILURE_MARKERS.any { marker -> message.contains(marker) }) {
                return null
            }
            return AjaxOutcome.APPLIED
        }

        private fun hasFavoritesPageStructure(html: String): Boolean =
            html.contains("datatable", ignoreCase = true) ||
                html.contains("my.php?item=", ignoreCase = true) ||
                html.contains("nodata", ignoreCase = true) ||
                html.contains("暂无数据")
    }
}
