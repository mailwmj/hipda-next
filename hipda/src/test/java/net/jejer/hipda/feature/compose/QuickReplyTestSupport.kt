package net.jejer.hipda.feature.compose

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.ImageUploadInput
import net.jejer.hipda.data.post.ImageUploadProgress
import net.jejer.hipda.data.post.ImageUploadRepository
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import okhttp3.OkHttpClient

internal class FakeQuickReplyAuthRepository(
    initialAccount: SavedAccount? = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 100L),
) : AuthRepository {
    private val _current = MutableStateFlow(initialAccount)
    private val _state = MutableStateFlow<AuthState>(
        if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
    )
    override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
    override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initialAccount)).asStateFlow()
    override val authState: StateFlow<AuthState> = _state.asStateFlow()
    override val sessionEpoch: Long = 1L

    fun setSignedOut() {
        _current.value = null
        _state.value = AuthState.SignedOut
    }

    fun setSignedIn(account: SavedAccount = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 200L)) {
        _current.value = account
        _state.value = AuthState.SignedIn(account)
    }

    override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
    override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
    override suspend fun logout() = setSignedOut()
    override suspend fun removeAccount(uid: String) = Unit
    override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
}

internal class FakePostRepository(
    private val nowMs: () -> Long = { 10_000L },
    initialDrafts: Map<String, DraftEntity> = emptyMap(),
) : PostRepository {
    private val draftsFlow = MutableStateFlow(initialDrafts.toMap())
    private val _cooldownUntilMs = MutableStateFlow(0L)
    override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

    val saveDraftCalls = CopyOnWriteArrayList<DraftEntity>()
    val deleteDraftCalls = CopyOnWriteArrayList<DraftKey>()
    val prePostRequests = CopyOnWriteArrayList<PrePostRequest>()
    val submitRequests = CopyOnWriteArrayList<PostSubmitRequest>()
    var prePostHandler: suspend (PrePostRequest) -> PrePostResult = {
        PrePostResult.Success(defaultSamplePrePostInfo())
    }
    var submitHandler: suspend (PostSubmitRequest) -> PostSubmitResult = { req ->
        val tid = when (req) {
            is PostSubmitRequest.ReplyThread -> req.tid
            is PostSubmitRequest.ReplyPost -> req.tid
            is PostSubmitRequest.QuotePost -> req.tid
            is PostSubmitRequest.EditPost -> req.tid
            is PostSubmitRequest.DeletePost -> req.tid
            is PostSubmitRequest.NewThread -> "990001"
        }
        PostSubmitResult.Success(tid = tid)
    }

    fun setCooldownUntil(untilMs: Long) {
        _cooldownUntilMs.value = untilMs
    }

    fun draftFor(key: DraftKey): DraftEntity? = draftsFlow.value[key.value]

    override fun getRemainingCooldownSeconds(): Int {
        val rem = _cooldownUntilMs.value - nowMs()
        return if (rem <= 0L) 0 else ((rem + 999L) / 1_000L).toInt()
    }

    override suspend fun fetchPrePost(request: PrePostRequest, priority: AccessPriority): PrePostResult {
        prePostRequests += request
        return prePostHandler(request)
    }

    override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult {
        submitRequests += request
        val result = submitHandler(request)
        if (result is PostSubmitResult.Success) {
            request.draftKey?.let { deleteDraft(it) }
        }
        return result
    }

    override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = draftsFlow.map { it[key.value] }

    override suspend fun saveDraft(
        key: DraftKey,
        subject: String,
        typeId: String,
        content: String,
        quotePid: String?,
        attachments: List<String>,
    ) {
        // 严格模拟真实 DefaultPostRepository.saveDraft：全空白直接 return 不写库（锁定 R-D6 S2 真实语义）
        val cleanedAttachments = attachments.map { it.trim() }.filter { it.isNotEmpty() }
        if (subject.isBlank() && content.isBlank() && cleanedAttachments.isEmpty()) return
        val entity = DraftEntity(key.value, subject, typeId, content, quotePid?.trim()?.ifEmpty { null }, cleanedAttachments, nowMs())
        saveDraftCalls += entity
        draftsFlow.value = draftsFlow.value + (key.value to entity)
    }

    override suspend fun deleteDraft(key: DraftKey) {
        deleteDraftCalls += key
        draftsFlow.value = draftsFlow.value - key.value
    }
}

internal data class RecordedSnackbar(
    val message: String,
    val target: PaneSnackbarTarget,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

internal class RecordingPaneSnackbarController : PaneSnackbarController {
    val events = CopyOnWriteArrayList<RecordedSnackbar>()
    override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) {
        events += RecordedSnackbar(message, target, actionLabel, onAction)
    }
}

internal class FakeComposeThreadDetailRepository(
    private val totalPages: Int = 3,
    private val floorsPerPage: Int = 10,
) : ThreadDetailRepository {
    val extraLastPageFloors = CopyOnWriteArrayList<PostFloor>()

    override suspend fun loadPage(
        tid: String,
        page: Int,
        filter: ThreadDetailFilter,
        recordHistory: Boolean,
        forumName: String?,
        priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val resolvedPage = page.coerceIn(1, totalPages)
        val baseFloors = (1..floorsPerPage).map { idx ->
            val floorNum = (resolvedPage - 1) * floorsPerPage + idx
            val uid = if (idx == 1) "1001" else "${1000 + idx}"
            val author = if (idx == 1) "linus_hi" else "geek_reader_$idx"
            PostFloor(
                postId = "${resolvedPage * 100 + idx}",
                floor = floorNum,
                page = resolvedPage,
                uid = uid,
                author = author,
                avatarUrl = null,
                timePost = "2026-11-26 12:${idx.toString().padStart(2, '0')}",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = listOf(
                    ContentBlock.Text(
                        listOf(
                            InlineSpan.Plain("第 $resolvedPage 页第 $floorNum 楼正文：测试快速回复面板与帖子阅读位置联动。"),
                        ),
                    ),
                ),
            )
        }
        val combined = if (resolvedPage == totalPages) baseFloors + extraLastPageFloors else baseFloors
        val filtered = when (filter) {
            ThreadDetailFilter.All -> combined
            is ThreadDetailFilter.AuthorOnly -> combined.filter { it.uid == (filter.authorId ?: "1001") }
            is ThreadDetailFilter.UserOnly -> combined.filter { it.uid == filter.uid }
        }
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(
                tid = tid,
                fid = 2,
                title = "快速回复面板测试主题",
                page = resolvedPage,
                lastPage = if (filter is ThreadDetailFilter.All) totalPages else 1,
                floors = filtered,
                postsPerPageEstimate = floorsPerPage,
                meta = composeSampleMeta(),
                hasPostList = true,
            ),
            filter = filter,
            threadAuthorId = "1001",
        )
    }

    override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid, totalPages, ThreadDetailFilter.All, recordHistory, forumName, priority)

    override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
        val page = ((pid.toIntOrNull() ?: 101) / 100).coerceIn(1, totalPages)
        val res = loadPage(tid ?: "2605001", page, ThreadDetailFilter.All, recordHistory, forumName, priority)
        return if (res is ThreadDetailLoadResult.Success) res.copy(targetPostId = pid) else res
    }

    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
    override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
    override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
        floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
    override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
    override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
    override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
    override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
        throw UnsupportedOperationException()
}

internal fun composeSampleMeta() = DiscuzPageMeta(
    session = DiscuzSessionInfo(true, "1001", "linus_hi"),
    formhash = "fh_compose",
    errorMessage = null,
    promptCounts = PromptCounts(),
)

internal fun createIdleComposeGateway() = SiteAccessGateway(
    client = OkHttpClient(),
    detector = ChallengeDetector(),
    solver = ChallengeSolver { SolveResult.Cancelled },
)

internal fun defaultSamplePrePostInfo(
    typeOptions: Map<String, String> = emptyMap(),
    typeId: String? = "0",
): PrePostInfo = PrePostInfo(
    formhash = "fh_compose",
    uid = "1001",
    hash = "uploadhash123456",
    subject = null,
    text = "",
    quoteText = "",
    isDeletable = false,
    maxUploadFileSizeBytes = 2 * 1024 * 1024,
    noticeAuthor = null,
    noticeAuthorMsg = null,
    noticeTrimStr = null,
    existingImages = emptyList(),
    existingAttachments = emptyList(),
    typeId = typeId,
    typeOptions = typeOptions,
    meta = composeSampleMeta(),
)

internal class FakeImageUploadRepository(
    var uploadFlowFactory: (ImageUploadInput, String, String, Int?) -> Flow<ImageUploadProgress> = { _, _, _, _ ->
        kotlinx.coroutines.flow.flowOf(
            ImageUploadProgress.Compressing,
            ImageUploadProgress.Uploading(1024L, 1024L, 1f),
            ImageUploadProgress.Succeeded(imageId = "1721001", compressedBytes = 1024L, wasCompressed = true),
        )
    },
) : ImageUploadRepository {
    val calls = CopyOnWriteArrayList<ImageUploadInput>()
    override fun uploadImage(
        input: ImageUploadInput,
        uid: String,
        hash: String,
        maxUploadBytes: Int?,
        priority: AccessPriority,
    ): Flow<ImageUploadProgress> {
        calls += input
        return uploadFlowFactory(input, uid, hash, maxUploadBytes)
    }
}

internal class FakeComposeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = flow
    override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
    override suspend fun setPureBlack(enabled: Boolean) = Unit
    override suspend fun setThemeSeed(themeSeed: String) = Unit
    override suspend fun setFontStep(step: Int) = Unit
    override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
    override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
    override suspend fun setShowStickyThreads(show: Boolean) = Unit
    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
    override suspend fun setTailEnabled(enabled: Boolean) = Unit
    override suspend fun setTailText(text: String) = Unit
    override suspend fun setTailUrl(url: String) = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
    override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
    override suspend fun setSilentEndMinutes(minutes: Int) = Unit
    override suspend fun setForumServer(server: String) = Unit
    override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
    override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
    override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
}

internal fun androidx.compose.ui.semantics.SemanticsNode.firstChildLayoutHeightPx(): Int? {
    val info = layoutInfo
    val getter = info.javaClass.methods.firstOrNull {
        (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
    }
    val firstChild = (getter?.invoke(info) as? List<*>)
        ?.filterIsInstance<androidx.compose.ui.layout.LayoutInfo>()
        ?.firstOrNull()
    return firstChild?.height
}
