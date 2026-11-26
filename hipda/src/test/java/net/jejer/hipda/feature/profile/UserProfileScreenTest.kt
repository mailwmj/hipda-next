package net.jejer.hipda.feature.profile

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.user.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.user.UserProfile
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.state.HiLoadState
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class UserProfileScreenTest {

    private val emptyMeta = DiscuzPageMeta(
        session = DiscuzSessionInfo(true, "10000", "HiUser"),
        formhash = "fh1",
        errorMessage = null,
        promptCounts = PromptCounts(0, 0),
    )

    @Test
    fun profileTabs_maintainIndependentStatesEmptyThreadsAndZeroGlass() = withAnimatorDurationScale(0f) {
        val profile = UserProfile(
            uid = "10000",
            username = "HiUser",
            isOnline = true,
            avatarUrl = "file:///etc/passwd", // 危险头像应被白名单拦截为 null
            formhash = "fh1",
            detail = "基本资料\n\n用户组: 初级会员\n注册日期: 2011-4-13\n积分: 6\n签名: https://example.com javascript:alert(1)\n",
            meta = emptyMeta,
        )
        val userRepo = FakeUserRepository(
            profileResult = UserProfileResult.Success(profile),
            threadsResult = UserThreadsResult.Success("10000", 1, SearchPage(null, 1, emptyList(), emptyMeta)),
        )
        val blRepo = FakeProfileBlacklistRepo()
        val vm = UserProfileViewModel("10000", "HiUser", userRepo, blRepo)
        val openedSms = mutableListOf<SmsConversationArgs>()

        setContent {
            HiTheme {
                UserProfileScreen(
                    viewModel = vm,
                    showBack = true,
                    onBack = {},
                    onOpenThread = {},
                    onOpenSmsConversation = { openedSms += it },
                    onOpenLogin = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        assertNull("危险头像 scheme 必须被拦截", vm.uiState.value.avatarUrl)

        onAllNodesWithText("HiUser").onFirst().assertIsDisplayed()
        onNodeWithText("用户组: 初级会员").assertIsDisplayed()

        // 发短消息回调
        onNodeWithText("发短消息").performClick()
        assertEquals(listOf(SmsConversationArgs("10000", "HiUser")), openedSms)

        // 切至 TA 的帖子：为空时显示“TA 还没有发过帖子”，不报错
        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("TA 还没有发过帖子").assertIsDisplayed()
        onNodeWithText("加载失败").assertDoesNotExist()

        // 切回资料无闪烁、不重复拉资料
        onNodeWithText("资料").performClick()
        waitForIdle()
        onNodeWithText("用户组: 初级会员").assertIsDisplayed()
        assertEquals(1, userRepo.profileCalls)
    }

    @Test
    fun profileThreadsClickAndBlacklistToggleWith0msSpinnerAndUndo() = withAnimatorDurationScale(0f) {
        val profile = UserProfile("10000", "HiUser", false, "https://img.example.com/a.jpg", "fh1", "基本资料\n\n用户组: 正式会员\n", emptyMeta)
        val items = listOf(SearchItem("3001", null, "测试用户帖子标题", null, "HiUser", "10000", null, "2026-11-20", "Discovery"))
        val userRepo = FakeUserRepository(
            profileResult = UserProfileResult.Success(profile),
            threadsResult = UserThreadsResult.Success("10000", 1, SearchPage("s1", 1, items, emptyMeta)),
        )
        val addGate = CompletableDeferred<BlacklistMutationResult>()
        val blRepo = FakeProfileBlacklistRepo(pendingAdd = addGate)
        val vm = UserProfileViewModel("10000", "HiUser", userRepo, blRepo)
        val openedThreads = mutableListOf<ThreadNavArgs>()

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        UserProfileScreen(vm, true, {}, { openedThreads += it }, {}, {}, PaddingValues(0.dp))
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()

        // 点击加入黑名单 -> 0ms 原地进度圈并禁用按钮
        mainClock.autoAdvance = false
        onNodeWithText("加入黑名单").performClick()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("加入黑名单").assertDoesNotExist()
        assertEquals(listOf("HiUser"), blRepo.addCalls)

        // 完成后显示可撤销 Snackbar 并切换为“移出黑名单”
        mainClock.autoAdvance = true
        blRepo.applyAdd("HiUser")
        addGate.complete(BlacklistMutationResult.Success("HiUser", blRepo.blockedUsernames.value))
        waitForIdle()
        onNodeWithText("移出黑名单").assertIsDisplayed()
        onNodeWithText("已将 HiUser 加入黑名单").assertIsDisplayed()

        // 点击撤销恢复为未拉黑
        onNodeWithText("撤销").performClick()
        waitForIdle()
        assertEquals(listOf("HiUser"), blRepo.removeCalls)
        onNodeWithText("加入黑名单").assertIsDisplayed()

        // 连续两次拉黑/移出操作：第二次发起时立即关掉前一条 Snackbar，点撤销仅撤回第二次
        blRepo.clearPendingAdd()
        onNodeWithText("加入黑名单").performClick()
        waitForIdle()
        onNodeWithText("已将 HiUser 加入黑名单").assertIsDisplayed()

        onNodeWithText("移出黑名单").performClick()
        waitForIdle()
        onNodeWithText("已将 HiUser 加入黑名单").assertDoesNotExist()
        onNodeWithText("已将 HiUser 移出黑名单").assertIsDisplayed()

        onNodeWithText("撤销").performClick()
        waitForIdle()
        onNodeWithText("移出黑名单").assertIsDisplayed()

        // 切至 TA 的帖子并点击打开帖子
        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        onNodeWithText("测试用户帖子标题").assertIsDisplayed().performClick()
        assertEquals("3001", openedThreads.single().tid)
    }

    @Test
    fun profileInfoError_doesNotBreakThreadsTab() = withAnimatorDurationScale(0f) {
        val items = listOf(SearchItem("4001", null, "独立帖子标题", null, "Bob", "20000", null, "2026-11-25", "Geek Talks"))
        val userRepo = FakeUserRepository(
            profileResult = UserError.NetworkError("timeout"),
            threadsResult = UserThreadsResult.Success("20000", 1, SearchPage("s2", 1, items, emptyMeta)),
        )
        val vm = UserProfileViewModel("20000", "Bob", userRepo, FakeProfileBlacklistRepo())

        setContent {
            HiTheme {
                UserProfileScreen(vm, true, {}, {}, {}, {}, PaddingValues(0.dp))
            }
        }
        waitForIdle()
        onNodeWithText("网络连接失败").assertIsDisplayed()

        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        onNodeWithText("独立帖子标题").assertIsDisplayed()
    }

    /** #3 & #4：资料页点顶栏标题滚回当前活跃标签顶部（含减动效 0ms 单帧校验），返回不误触发滚顶；TA 的帖子长按直达末页。 */
    @Test
    fun profileTopBar_scrollsActiveTabToTop_andThreadsLongPressOpensLastPage() = withAnimatorDurationScale(0f) {
        val longDetail = (1..20).joinToString("\n\n") { i -> "分区$i\n\n字段$i: 内容$i" }
        val parsed = parseProfileDetails(longDetail)
        val threads = (1..25).map { i -> SearchItem("${5000 + i}", "${70000 + i}", "TA的测试帖 $i", null, "Carol", "30000", null, "2026-11-26", "Discovery") }
        var selectedTab by mutableStateOf(UserProfileTab.Info)
        var infoState: LazyListState? = null
        var threadsState: LazyListState? = null
        var backClicks = 0
        val opened = mutableListOf<ThreadNavArgs>()

        setContent {
            val iState = rememberLazyListState()
            val tState = rememberLazyListState()
            infoState = iState
            threadsState = tState
            HiTheme {
                UserProfileScreenContent(
                    uiState = UserProfileUiState(
                        uid = "30000",
                        displayUsername = "Carol",
                        selectedTab = selectedTab,
                        profileLoadState = HiLoadState.Content(parsed),
                        threadsLoadState = HiLoadState.Content(threads),
                        hasLoadedThreads = true,
                    ),
                    showBack = true,
                    onBack = { backClicks++ },
                    onSelectTab = { selectedTab = it },
                    onRetryProfile = {},
                    onRetryThreads = {},
                    onLoadMoreThreads = {},
                    onOpenSms = {},
                    onToggleBlacklist = {},
                    onOpenThread = opened::add,
                    onOpenLogin = {},
                    contentPadding = PaddingValues(0.dp),
                    infoListState = iState,
                    threadsListState = tState,
                )
            }
        }
        waitForIdle()

        // 1. 资料标签滚到中段 -> 返回不滚顶 -> 点顶栏标题一帧滚回 (0, 0)
        val infoList = requireNotNull(infoState)
        runOnIdle { infoList.requestScrollToItem(10, 20) }
        waitForIdle()
        assertTrue(infoList.firstVisibleItemIndex >= 10)

        onNodeWithContentDescription("返回").performClick()
        waitForIdle()
        assertEquals(1, backClicks)
        assertTrue("点击返回箭头不得误触发滚顶", infoList.firstVisibleItemIndex >= 10)

        mainClock.autoAdvance = false
        try {
            onAllNodesWithText("Carol").onFirst().performClick()
            mainClock.advanceTimeByFrame()
            assertEquals(0, infoList.firstVisibleItemIndex)
            assertEquals(0, infoList.firstVisibleItemScrollOffset)
        } finally {
            mainClock.autoAdvance = true
        }

        // 2. 切到 TA 的帖子：单击 openLastPage=false、长按 openLastPage=true，并验证顶栏点按滚回 TA 的帖子顶部
        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        val firstThread = onNodeWithText("TA的测试帖 1").assertIsDisplayed()
        firstThread.performClick()
        assertEquals(false, opened.last().openLastPage)
        assertEquals("5001", opened.last().tid)

        firstThread.performTouchInput { longClick() }
        waitForIdle()
        assertEquals(true, opened.last().openLastPage)
        assertEquals("5001", opened.last().tid)

        val threadsList = requireNotNull(threadsState)
        runOnIdle { threadsList.requestScrollToItem(12, 30) }
        waitForIdle()
        assertTrue(threadsList.firstVisibleItemIndex >= 12)

        mainClock.autoAdvance = false
        try {
            onAllNodesWithText("Carol").onFirst().performClick()
            mainClock.advanceTimeByFrame()
            assertEquals(0, threadsList.firstVisibleItemIndex)
            assertEquals(0, threadsList.firstVisibleItemScrollOffset)
        } finally {
            mainClock.autoAdvance = true
        }
    }

    /** #8：有头像时点大头像清除内存（含 extras 键）与磁盘缓存、绕过旧缓存触发新请求并弹出「头像已经刷新」；再次点击仍触发重载。 */
    @Test
    fun profileAvatarClick_withAvatar_evictsMemoryAndDiskCacheReloadsAndShowsToast() = withAnimatorDurationScale(0f) {
        val avatarUrl = "https://img.example.com/avatar_10000.jpg"
        val otherUrl = "https://img.example.com/avatar_other.jpg"
        val cacheDir = createTempDirectory("profile-avatar-cache").toFile()
        val fetchedRequests = CopyOnWriteArrayList<ImageRequest>()
        val memoryCache = MemoryCache.Builder().maxSizeBytes(4 * 1024 * 1024).build()
        val diskCache = DiskCache.Builder().directory(cacheDir.toOkioPath()).maxSizeBytes(4 * 1024 * 1024).build()
        val imageLoader = buildRecordingImageLoader(memoryCache, diskCache, fetchedRequests)

        try {
            // 预置内存缓存哨兵（含裸键与带尺寸 extras 键，以及无关用户头像键）
            val sentinelImg = ColorImage(color = AndroidColor.RED, width = 64, height = 64)
            val sizedKey = MemoryCache.Key(avatarUrl, mapOf("coil#size" to "128x128"))
            val bareKey = MemoryCache.Key(avatarUrl)
            val otherMemKey = MemoryCache.Key(otherUrl)
            memoryCache[bareKey] = MemoryCache.Value(sentinelImg)
            memoryCache[sizedKey] = MemoryCache.Value(sentinelImg)
            memoryCache[otherMemKey] = MemoryCache.Value(sentinelImg)

            // 预置磁盘缓存哨兵（目标用户与无关用户）
            writeDiskCacheSentinel(diskCache, avatarUrl, "OLD_AVATAR_BYTES")
            writeDiskCacheSentinel(diskCache, otherUrl, "OTHER_AVATAR_BYTES")
            assertNotNull(diskCache.openSnapshot(avatarUrl)?.also { it.close() })

            val profile = UserProfile("10000", "HiUser", true, avatarUrl, "fh1", "基本资料\n\n用户组: 正式会员\n", emptyMeta)
            val vm = UserProfileViewModel(
                "10000",
                "HiUser",
                FakeUserRepository(UserProfileResult.Success(profile), UserThreadsResult.Success("10000", 1, SearchPage(null, 1, emptyList(), emptyMeta))),
                FakeProfileBlacklistRepo(),
            )

            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme {
                    CompositionLocalProvider(
                        LocalHiImageLoader provides imageLoader,
                        LocalPaneSnackbarController provides controller,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            UserProfileScreen(vm, true, {}, {}, {}, {}, PaddingValues(0.dp))
                            HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                        }
                    }
                }
            }
            waitForIdle()
            val fetchesBeforeClick = fetchedRequests.size

            // 点击大头像：清理内存 + 磁盘缓存、以 WRITE_ONLY 策略触发 1 次新请求并提示「头像已经刷新」
            onNodeWithContentDescription("头像").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5_000L) {
                fetchedRequests.size > fetchesBeforeClick &&
                    onAllNodesWithText("头像已经刷新").fetchSemanticsNodes().isNotEmpty()
            }

            assertNull("带 extras 的旧内存缓存键必须被清除", memoryCache[sizedKey])
            assertNull("目标裸内存缓存键在重载前已被移除（自定义拦截器未回填）", memoryCache[bareKey])
            assertNotNull("无关用户的内存缓存不得被误清", memoryCache[otherMemKey])
            assertNull("目标头像旧磁盘缓存哨兵必须被精确移除", diskCache.openSnapshot(avatarUrl)?.also { it.close() })
            assertNotNull("无关用户的磁盘缓存不得被误清", diskCache.openSnapshot(otherUrl)?.also { it.close() })
            val refreshReq = fetchedRequests.last()
            assertEquals(avatarUrl, refreshReq.data)
            assertEquals(avatarUrl, refreshReq.memoryCacheKey)
            assertEquals(avatarUrl, refreshReq.diskCacheKey)
            assertEquals(CachePolicy.WRITE_ONLY, refreshReq.memoryCachePolicy)
            assertEquals(CachePolicy.WRITE_ONLY, refreshReq.diskCachePolicy)
            onNodeWithText("头像已经刷新").assertIsDisplayed()

            // 第二次点击大头像：nonce 递增确保 ImageRequest 不被 equals 去重，再次发起新请求
            val fetchesAfterFirst = fetchedRequests.size
            onNodeWithContentDescription("头像").performClick()
            waitUntil(timeoutMillis = 5_000L) { fetchedRequests.size > fetchesAfterFirst }
            assertEquals(fetchesAfterFirst + 1, fetchedRequests.size)
        } finally {
            imageLoader.shutdown()
            diskCache.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    /** #8：无头像时点大头像不清理缓存、0 新请求并弹出「用户未设置头像」。 */
    @Test
    fun profileAvatarClick_withoutAvatar_preservesCacheZeroRequestsAndShowsNotSetToast() = withAnimatorDurationScale(0f) {
        val otherUrl = "https://img.example.com/avatar_keep.jpg"
        val cacheDir = createTempDirectory("profile-no-avatar-cache").toFile()
        val fetchedRequests = CopyOnWriteArrayList<ImageRequest>()
        val memoryCache = MemoryCache.Builder().maxSizeBytes(4 * 1024 * 1024).build()
        val diskCache = DiskCache.Builder().directory(cacheDir.toOkioPath()).maxSizeBytes(4 * 1024 * 1024).build()
        val imageLoader = buildRecordingImageLoader(memoryCache, diskCache, fetchedRequests)

        try {
            val otherMemKey = MemoryCache.Key(otherUrl)
            memoryCache[otherMemKey] = MemoryCache.Value(ColorImage(color = AndroidColor.BLUE, width = 64, height = 64))
            writeDiskCacheSentinel(diskCache, otherUrl, "KEEP_BYTES")

            val profile = UserProfile("10001", "NoAvatarUser", false, null, "fh1", "基本资料\n\n用户组: 初级会员\n", emptyMeta)
            val vm = UserProfileViewModel(
                "10001",
                "NoAvatarUser",
                FakeUserRepository(UserProfileResult.Success(profile), UserThreadsResult.Success("10001", 1, SearchPage(null, 1, emptyList(), emptyMeta))),
                FakeProfileBlacklistRepo(),
            )

            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme {
                    CompositionLocalProvider(
                        LocalHiImageLoader provides imageLoader,
                        LocalPaneSnackbarController provides controller,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            UserProfileScreen(vm, true, {}, {}, {}, {}, PaddingValues(0.dp))
                            HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithContentDescription("头像").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithText("用户未设置头像").fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals("无头像时不得发起任何头像网络请求", 0, fetchedRequests.size)
            assertNotNull("无头像时不得误清内存缓存", memoryCache[otherMemKey])
            assertNotNull("无头像时不得误清磁盘缓存", diskCache.openSnapshot(otherUrl)?.also { it.close() })
            onNodeWithText("用户未设置头像").assertIsDisplayed()
        } finally {
            imageLoader.shutdown()
            diskCache.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    private fun buildRecordingImageLoader(
        memoryCache: MemoryCache,
        diskCache: DiskCache,
        fetchedRequests: CopyOnWriteArrayList<ImageRequest>,
    ): ImageLoader {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return ImageLoader.Builder(context)
            .memoryCache { memoryCache }
            .diskCache { diskCache }
            .components {
                add(
                    Interceptor { chain ->
                        fetchedRequests += chain.request
                        SuccessResult(
                            image = ColorImage(color = AndroidColor.GREEN, width = 64, height = 64),
                            request = chain.request,
                        )
                    },
                )
            }
            .build()
    }

    private fun writeDiskCacheSentinel(diskCache: DiskCache, key: String, payload: String) {
        val editor = requireNotNull(diskCache.openEditor(key))
        try {
            File(editor.data.toString()).writeText(payload)
            editor.commit()
        } catch (t: Throwable) {
            editor.abort()
            throw t
        }
    }

    private class FakeUserRepository(
        private val profileResult: UserProfileResult,
        private val threadsResult: UserThreadsResult,
    ) : UserRepository {
        var profileCalls = 0
        override suspend fun getUserProfile(uid: String, priority: AccessPriority): UserProfileResult {
            profileCalls++
            return profileResult
        }
        override suspend fun getUserThreads(uid: String, page: Int, searchId: String?, priority: AccessPriority): UserThreadsResult = threadsResult
    }

    private class FakeProfileBlacklistRepo(
        private var pendingAdd: CompletableDeferred<BlacklistMutationResult>? = null,
    ) : BlacklistRepository {
        private val _blocked = MutableStateFlow<Set<String>>(emptySet())
        override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null).asStateFlow()
        val addCalls = mutableListOf<String>()
        val removeCalls = mutableListOf<String>()

        override fun isBlocked(username: String): Boolean = username in _blocked.value
        override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
            BlacklistSyncResult.Success(BlacklistSnapshot("1", _blocked.value, 1L), _blocked.value.isEmpty())

        fun applyAdd(username: String) { _blocked.update { it + username } }
        fun clearPendingAdd() { pendingAdd = null }

        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            addCalls += username
            pendingAdd?.let { return it.await() }
            _blocked.update { it + username }
            return BlacklistMutationResult.Success(username, _blocked.value)
        }

        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            removeCalls += username
            _blocked.update { it - username }
            return BlacklistMutationResult.Success(username, _blocked.value)
        }
    }
}
