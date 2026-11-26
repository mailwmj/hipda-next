package net.jejer.hipda.feature.app

import android.app.Activity
import android.content.Context
import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowInfoTrackerDecorator
import androidx.window.layout.WindowLayoutInfo
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
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
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.TextFormat
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue

internal class MultiPaneFakeFoldingFeature(
    override val bounds: AndroidRect,
    override val orientation: FoldingFeature.Orientation,
    override val state: FoldingFeature.State,
    override val isSeparating: Boolean,
    override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.NONE,
) : FoldingFeature

@Suppress("RestrictedApi")
internal fun withWindowLayoutFlow(
    layoutFlow: Flow<WindowLayoutInfo>,
    block: () -> Unit,
) {
    val decorator = object : WindowInfoTrackerDecorator {
        override fun decorate(tracker: WindowInfoTracker): WindowInfoTracker = object : WindowInfoTracker {
            override fun windowLayoutInfo(activity: Activity): Flow<WindowLayoutInfo> = layoutFlow
            override fun windowLayoutInfo(context: Context): Flow<WindowLayoutInfo> = layoutFlow
        }
    }
    WindowInfoTracker.overrideDecorator(decorator)
    try {
        block()
    } finally {
        WindowInfoTracker.reset()
    }
}

internal fun verticalHingeLayoutInfo(
    leftDp: Float,
    rightDp: Float,
    heightDp: Float,
    density: Float,
    isSeparating: Boolean,
    state: FoldingFeature.State = if (isSeparating) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT,
): WindowLayoutInfo = WindowLayoutInfo(
    listOf(
        MultiPaneFakeFoldingFeature(
            bounds = AndroidRect(
                (leftDp * density).roundToInt(),
                0,
                (rightDp * density).roundToInt(),
                (heightDp * density).roundToInt(),
            ),
            orientation = FoldingFeature.Orientation.VERTICAL,
            state = state,
            isSeparating = isSeparating,
        ),
    ),
)

@Composable
internal fun MultiPaneResizableWindowHost(windowSize: DpSize, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val base = LocalWindowInfo.current
    val fakeWindowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = base.isWindowFocused
        override val containerDpSize: DpSize get() = windowSize
        override val containerSize: IntSize
            get() = with(density) { IntSize(windowSize.width.toPx().roundToInt(), windowSize.height.toPx().roundToInt()) }
    }
    CompositionLocalProvider(LocalWindowInfo provides fakeWindowInfo) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

internal data class GlassModifierAudit(
    val layerBackdropCount: Int,
    val nestedLayerBackdropCount: Int,
    val drawBackdropCount: Int,
    val visitedNodes: Int,
)

internal fun auditGlassModifiers(root: LayoutInfo): GlassModifierAudit {
    var visited = 0
    var layerCount = 0
    var nestedLayerCount = 0
    var drawCount = 0

    fun walk(node: LayoutInfo, insideLayerBackdrop: Boolean) {
        visited += 1
        val modifiers = node.getModifierInfo()
        val hasLayer = modifiers.any { it.modifier.javaClass.name.contains("LayerBackdrop", ignoreCase = true) }
        val drawHits = modifiers.count { it.modifier.javaClass.name.contains("DrawBackdrop", ignoreCase = true) }
        if (hasLayer) {
            layerCount += 1
            if (insideLayerBackdrop) nestedLayerCount += 1
        }
        drawCount += drawHits
        val getter = node.javaClass.methods.firstOrNull {
            (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
        }
        val children = (getter?.invoke(node) as? List<*>).orEmpty()
        for (child in children) {
            if (child is LayoutInfo) walk(child, insideLayerBackdrop || hasLayer)
        }
    }

    walk(root, insideLayerBackdrop = false)
    assertTrue("LayoutNode 子树遍历必须覆盖真实子节点，实际 visited=$visited", visited > 5)
    return GlassModifierAudit(layerCount, nestedLayerCount, drawCount, visited)
}

internal class MultiPaneTestHarness(
    initialSettings: AppSettings = AppSettings(),
    private val richSampleContent: Boolean = false,
) {
    val settingsRepo = RecordingSettingsRepository(initialSettings)
    val authRepo = OfflineAuthRepository()
    val smsUnread = MutableStateFlow(2)
    val notifyUnread = MutableStateFlow(5)
    val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
    val threadListRepo = OfflineThreadListRepository(richSampleContent)
    val searchRepo = OfflineSearchRepository()
    val threadDetailRepo = OfflineThreadDetailRepository(richSampleContent)
    val hostViewModel = NextAppHostViewModel(settingsRepo)
    val forumViewModel = ForumViewModel(threadListRepo, searchRepo, settingsRepo, authRepo, gateway)

    val createdDetailVms = mutableListOf<ThreadDetailViewModel>()
    val clearedDetailVms = mutableListOf<ThreadDetailViewModel>()

    val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
            modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostViewModel as T
            modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumViewModel as T
            modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                ThreadDetailViewModel(threadDetailRepo, gateway).also { vm ->
                    createdDetailVms.add(vm)
                    vm.addCloseable(Closeable { clearedDetailVms.add(vm) })
                } as T
            else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
        }
    }

    val parentOwner: ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
    }

    @Composable
    fun RenderRoot(
        glassEnvironment: GlassEnvironmentState = GlassEnvironmentState.RefractionAndBlur,
        loginOverlayRenderer: LoginOverlayRenderer? = null,
    ) {
        ProvideGlassEnvironment(state = glassEnvironment) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = settingsRepo.settings,
                    currentAccountFlow = authRepo.currentAccount,
                    smsUnreadCountFlow = smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = notifyUnread.asStateFlow(),
                    loginOverlayRenderer = loginOverlayRenderer,
                    hostViewModel = hostViewModel,
                    forumViewModel = forumViewModel,
                )
            }
        }
    }
}

internal class RecordingSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val settingsFlow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = settingsFlow
    val portraitSplitWrites = mutableListOf<PaneSplit>()
    val landscapeSplitWrites = mutableListOf<PaneSplit>()

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsFlow.update(transform)

    override suspend fun setThemeMode(themeMode: ThemeMode) = settingsFlow.update { it.copy(themeMode = themeMode) }
    override suspend fun setPureBlack(enabled: Boolean) = settingsFlow.update { it.copy(pureBlack = enabled) }
    override suspend fun setThemeSeed(themeSeed: String) = settingsFlow.update { it.copy(themeSeed = themeSeed) }
    override suspend fun setFontStep(step: Int) = settingsFlow.update { it.copy(fontStep = step) }
    override suspend fun setLineSpacing(lineSpacing: LineSpacing) = settingsFlow.update { it.copy(lineSpacing = lineSpacing) }
    override suspend fun setFollowedForums(forumIds: List<Int>) = settingsFlow.update { it.copy(followedForums = forumIds) }
    override suspend fun setShowStickyThreads(show: Boolean) = settingsFlow.update { it.copy(showStickyThreads = show) }
    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) =
        settingsFlow.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
    override suspend fun setTailEnabled(enabled: Boolean) = Unit
    override suspend fun setTailText(text: String) = Unit
    override suspend fun setTailUrl(url: String) = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
    override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
    override suspend fun setSilentEndMinutes(minutes: Int) = Unit
    override suspend fun setForumServer(server: String) = Unit
    override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
    override suspend fun setPaneSplitPortrait(split: PaneSplit) {
        portraitSplitWrites.add(split)
        settingsFlow.update { it.copy(paneSplitPortrait = split) }
    }
    override suspend fun setPaneSplitLandscape(split: PaneSplit) {
        landscapeSplitWrites.add(split)
        settingsFlow.update { it.copy(paneSplitLandscape = split) }
    }
    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) =
        settingsFlow.update { it.copy(sidebarCollapsedPortrait = collapsed) }
    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) =
        settingsFlow.update { it.copy(sidebarCollapsedLandscape = collapsed) }
}

internal class OfflineAuthRepository : AuthRepository {
    private val _account = MutableStateFlow<SavedAccount?>(
        SavedAccount(uid = "42", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 1L),
    )
    override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
    override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(_account.value))
    override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn(_account.value!!))
    override val sessionEpoch: Long = 1L
    override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
    override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
    override suspend fun logout() { _account.value = null }
    override suspend fun removeAccount(uid: String) = Unit
    override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
}

internal class OfflineSearchRepository : SearchRepository {
    val newPostsCalls = AtomicInteger(0)
    override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun search(
        query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean,
    ): SearchResult = getNewPosts(page, searchId, priority)

    override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult {
        newPostsCalls.incrementAndGet()
        val items = (1..8).map { idx ->
            val tid = (9000 + idx).toString()
            SearchItem(tid, null, "今日新帖测试标题 $tid", null, "新帖作者$idx", idx.toString(), null, "刚刚", "Discovery")
        }
        return SearchResult.Success(page, false, SearchPage("sid-1", 2, items, offlinePageMeta()))
    }

    override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
    override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
    override suspend fun recordSearchHistory(query: String): List<String> = emptyList()
    override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
    override suspend fun restoreSearchHistory(query: String, index: Int): List<String> = emptyList()
    override suspend fun clearSearchHistory() = Unit
    override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
}

internal class OfflineThreadListRepository(private val richSampleContent: Boolean = false) : ThreadListRepository {
    val refreshCalls = AtomicInteger(0)
    private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
    private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

    override suspend fun loadPage(
        query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
    ): ThreadListLoadResult = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

    override suspend fun refresh(
        query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
    ): ThreadListLoadResult {
        refreshCalls.incrementAndGet()
        val summaries = if (richSampleContent && query.fid == 2) richThreads() else numberedThreads(query.fid)
        val items = summaries.mapIndexed { idx, s -> ThreadListItem(s, isVisited = richSampleContent && idx == 3) }
        stateFor(query).value = ThreadListLoadState(query, items, 1, 3, true, 1, offlinePageMeta(), isLoading = false)
        return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 3, true, 1, offlinePageMeta())
    }

    override suspend fun loadMore(
        query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
    ): ThreadListLoadResult = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

    override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
    override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>): Flow<List<ThreadListItem>> =
        rawThreads.map { list -> list.map { ThreadListItem(it, false) } }

    private fun numberedThreads(fid: Int): List<ThreadSummary> {
        val base = if (fid == 2) 1000 else fid * 1000
        return (1..30).map { i ->
            val tid = (base + i).toString()
            ThreadSummary(tid, "测试帖子标题 $tid", null, null, false, false, false, "作者$i", "$i", null, "2026-11-25", "刚刚", "回复者$i", "${i * 3}", "${i * 20}", false, 2)
        }
    }

    private fun richThreads(): List<ThreadSummary> = listOf(
        ThreadSummary("1001", "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验", null, "技术", false, true, false, "linus_hi", "1001", null, "2026-11-25", "2 分钟前", "geek_reader", "68", "1840", true, 3),
        ThreadSummary("1002", "入手一台二手便携墨水屏做随身书单记录，聊聊一周感受", null, "分享", false, false, false, "eink_fan", "1002", null, "2026-11-25", "12 分钟前", "momo", "42", "980", true, 2),
        ThreadSummary("1003", "家用 NAS 从 4 盘位换到静音全闪方案的功耗与噪音实测", null, "硬件", false, false, false, "nas_builder", "1003", null, "2026-11-25", "35 分钟前", "silent_pc", "115", "3120", false, 4),
        ThreadSummary("1004", "周末自驾川西小环线：路况、海拔与住宿避坑清单", null, "游记", false, false, false, "roadtrip", "1004", null, "2026-11-24", "1 小时前", "alpine", "29", "640", true, 1),
        ThreadSummary("1005", "咖啡手冲滤杯对比：V60、折纸与平底蛋糕杯风味差异", null, "生活", false, false, false, "barista_k", "1005", null, "2026-11-24", "3 小时前", "latte", "53", "1120", false, 2),
        ThreadSummary("1006", "请教：Android 16 折叠屏半折铰链态窗口分栏的最佳实践", null, "求助", false, false, true, "compose_dev", "1006", null, "2026-11-24", "5 小时前", "android_x", "37", "890", false, 2),
    )
}

internal class OfflineThreadDetailRepository(private val richSampleContent: Boolean = false) : ThreadDetailRepository {
    val loadPageCalls = AtomicInteger(0)

    override suspend fun loadPage(
        tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
    ): ThreadDetailLoadResult {
        loadPageCalls.incrementAndGet()
        val floors = if (richSampleContent) richFloors(tid, page) else numberedFloors(tid, page)
        val title = if (richSampleContent && tid == "1001") "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验" else "测试帖子标题 $tid"
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(tid, 2, title, page, 3, floors, 30, offlinePageMeta(), true),
            filter = filter,
            forumName = forumName,
            threadAuthorId = floors.firstOrNull()?.uid,
        )
    }

    override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
    override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
    override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
        floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
    override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
    override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
    override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
    override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
        throw UnsupportedOperationException()

    private fun numberedFloors(tid: String, page: Int): List<PostFloor> = (1..25).map { i ->
        PostFloor(
            postId = "${tid}_$i", floor = i, page = page, uid = "$i", author = "楼层作者$i", avatarUrl = null,
            timePost = "2026-11-25 10:${i.toString().padStart(2, '0')}", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("帖子 $tid 第 $i 楼正文内容，用于验证多栏阅读与状态迁移。")))),
        )
    }

    private fun richFloors(tid: String, page: Int): List<PostFloor> = listOf(
        PostFloor(
            postId = "${tid}_1", floor = 1, page = page, uid = "1001", author = "linus_hi", avatarUrl = null,
            timePost = "2026-11-25 21:05", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Text(
                    listOf(
                        InlineSpan.Plain("在 749dp 紧凑三栏下，左栏固定 64dp 图标轨，中栏 274dp 紧凑列表，右栏保留 411dp 正文阅读宽度。"),
                        InlineSpan.LineBreak,
                        InlineSpan.Plain("中栏与右栏各自持有独立的玻璃采样源，滚动互不干扰。", TextFormat(bold = true)),
                    ),
                ),
            ),
        ),
        PostFloor(
            postId = "${tid}_2", floor = 2, page = page, uid = "1002", author = "geek_reader", avatarUrl = null,
            timePost = "2026-11-25 21:14", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Quote("linus_hi 发表于 2026-11-25 21:05", "中栏与右栏各自持有独立的玻璃采样源", "中栏与右栏各自持有独立的玻璃采样源，滚动互不干扰。", tid, "${tid}_1"),
                ContentBlock.Text(listOf(InlineSpan.Plain("半折书本态切入铰链双栏后，左顶栏自动退纯色守住全屏 3 块玻璃上限，体验非常连贯。"))),
            ),
        ),
        PostFloor(
            postId = "${tid}_3", floor = 3, page = page, uid = "1003", author = "compose_dev", avatarUrl = null,
            timePost = "2026-11-25 21:28", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Text(listOf(InlineSpan.Plain("拖动中右分隔线可以平滑切到均衡档或收起列表，点右上角全屏阅读还能一键隐藏左中两栏专注读帖。"))),
            ),
        ),
    )
}

internal fun offlinePageMeta(): DiscuzPageMeta = DiscuzPageMeta(
    session = DiscuzSessionInfo(isLoggedIn = true, uid = "42", username = "HiUser"),
    formhash = "abc12345",
    errorMessage = null,
    promptCounts = PromptCounts(),
)
