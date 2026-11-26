package net.jejer.hipda.verify

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.favorite.DefaultFavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteCacheStore
import net.jejer.hipda.data.favorite.FavoriteError
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.favorite.FavoriteListResult
import net.jejer.hipda.data.favorite.FavoriteMutationResult
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteSyncResult
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.NotifyFeed
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.SmsClearOutcome
import net.jejer.hipda.data.message.SmsRepository
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
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
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.data.user.UserProfileResult
import net.jejer.hipda.data.user.UserRepository
import net.jejer.hipda.data.user.UserThreadsResult
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.login.LoginOverlayRendererImpl
import net.jejer.hipda.feature.messages.MessagesPaneRendererImpl
import net.jejer.hipda.feature.mine.SearchMinePaneRendererImpl
import net.jejer.hipda.feature.settings.SettingsProfilePaneRendererImpl
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.feature.threaddetail.chrome.ThreadBottomToolbar
import net.jejer.hipda.feature.threaddetail.chrome.buildSafeThreadUrl
import net.jejer.hipda.feature.threaddetail.chrome.copyThreadLinkPlainText
import net.jejer.hipda.feature.threaddetail.chrome.openExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.chrome.resolveProgressCapsuleColors
import net.jejer.hipda.feature.threaddetail.chrome.sanitizeExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.chrome.shareExternalHttpUrl
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyItem
import net.jejer.hipda.parser.message.NotifyKind
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem
import net.jejer.hipda.parser.simplelist.FavoritesListPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.parser.user.UserProfile
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.LocalHiGlassSuspended
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.glass.assertWcagContrastAtLeast
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.glass.resolveHiGlassStyleSpec
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * V-E4 独立行为与集成探针（不参考实现方单测，全部基于任务卡契约、产品设计规范与生产源码编写，100% 离线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class E4ProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun initOkHttp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    // -------------------------------------------------------------------------
    // 探针 a：慢加载恢复门闩防冲写、去抖落盘、PositionStack、E3 优先级与 R-E2c S1-R2
    // -------------------------------------------------------------------------

    @Test
    fun probeA1_slowLoadHydrationGate_blocksSavesBeforeHydrationAndEarlyExit_thenDebouncesAndPopsStack() {
        val repo = ProbeThreadDetailRepository().apply {
            preSavedPositions["8001"] = ReadingPositionEntity(
                tid = "8001",
                page = 5,
                pid = "p503",
                offsetPx = 120,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
            pageDelays["8001" to 5] = CompletableDeferred()
        }
        val auth = ProbeAuthRepository(signedInAccount())
        val settings = ProbeSettingsRepository()
        val fav = ProbeFavoriteRepository()

        var mountDetail by mutableStateOf(true)
        var backCalls = 0
        lateinit var listState: LazyListState
        lateinit var vm: ThreadDetailViewModel
        lateinit var scope: CoroutineScope
        val backOwner = mutableStateOf<androidx.activity.OnBackPressedDispatcherOwner?>(null)

        composeRule.setContent {
            scope = rememberCoroutineScope()
            backOwner.value = LocalOnBackPressedDispatcherOwner.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    if (mountDetail) {
                        vm = remember { ThreadDetailViewModel(repo, createOfflineGateway(), auth, fav, settings) }
                        listState = rememberLazyListState()
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "8001", title = "门闩探针帖"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = { backCalls += 1 },
                            onOpenLogin = {},
                            listState = listState,
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 挂起首次 loadPage(5)：推进虚拟时钟 + 尝试滚动上报 + 提前退出，硬断言 saveReadingPosition == 0
        composeRule.runOnIdle {
            vm.onReadingPositionChanged(page = 1, postId = "p101", scrollOffsetPx = 80)
            vm.flushReadingPosition()
        }
        advanceVirtualAndMainLooper(1_500L)
        composeRule.waitForIdle()
        assertEquals("恢复完成前严禁任何 saveReadingPosition 调用", 0, repo.saveCalls.size)

        // 模拟慢网中途离开帖子触发 onDispose flushReadingPosition()
        composeRule.runOnIdle { mountDetail = false }
        advanceVirtualAndMainLooper(1_000L)
        composeRule.waitForIdle()
        assertEquals("未水合即提前退出时 saveReadingPosition 必须严格 == 0", 0, repo.saveCalls.size)

        // 2. 重新挂载，挂起 loadPage(5) 后再放行
        val secondGate = CompletableDeferred<Unit>()
        repo.pageDelays["8001" to 5] = secondGate
        composeRule.runOnIdle { mountDetail = true }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(1_200L)
        assertEquals("二次挂载挂起期间仍严禁冲写", 0, repo.saveCalls.size)

        // 放行第 5 页并等待恢复定位到 p503 @ 120px
        secondGate.complete(Unit)
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(800L)
        composeRule.waitForIdle()

        val restoredFirstFloor = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.offset + it.size > 0 && (it.key as? String)?.startsWith("floor:") == true
        }
        assertEquals("恢复后首可见楼层应为 p503", "floor:p503", restoredFirstFloor?.key)
        assertTrue(
            "恢复像素偏移应接近预置 120px，实际 ${restoredFirstFloor?.offset}",
            kotlin.math.abs((-120) - (restoredFirstFloor?.offset ?: 0)) <= 4,
        )
        assertEquals("仅完成恢复定位、未继续滚动时不应重写入库", 0, repo.saveCalls.size)

        // 3. 恢复完成后滚动到 p506 @ 48px，去抖 400ms 前不写、去抖后准确落盘新位置
        val targetIndex = vm.uiState.value.floors.indexOfFirst { it.postId == "p506" }
        assertTrue(targetIndex >= 0)
        composeRule.runOnIdle {
            scope.launch { listState.scrollToItem(targetIndex, 48) }
        }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(200L)
        assertEquals("400ms 去抖窗口内尚未落盘", 0, repo.saveCalls.size)

        advanceVirtualAndMainLooper(400L)
        composeRule.waitUntil(3_000L) { repo.saveCalls.isNotEmpty() }
        val saved = repo.saveCalls.last()
        assertEquals("8001", saved.tid)
        assertEquals(5, saved.page)
        assertEquals("p506", saved.pid)
        assertEquals(48, saved.offsetPx)

        // 4. 从第 5 页点击底部进度胶囊展开内联跳页条 -> 点击“首页”
        composeRule.onNodeWithContentDescription("第 5 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertTrue("跳去首页前应已压入位置栈", vm.uiState.value.canPopPositionStack)

        // 5. 第 1 次系统返回：优先出栈回到第 5 页 p506，不得离开帖子
        composeRule.runOnIdle { backOwner.value!!.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("栈非空时返回不得离开帖子", 0, backCalls)
        assertEquals(5, vm.uiState.value.page)
        val poppedFloor = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.offset + it.size > 0 && (it.key as? String)?.startsWith("floor:") == true
        }
        assertEquals("出栈应精确回到第 5 页 p506", "floor:p506", poppedFloor?.key)
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 6. 栈空后 ThreadDetailPane 必须停止拦截系统返回，顶栏返回按钮可离开帖子
        assertFalse(
            "栈空后不得再注册 ThreadDetail BackHandler",
            backOwner.value!!.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertEquals("栈空后点击返回按钮应离开帖子", 1, backCalls)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probeA2_threePane_positionStackPopsBeforeE3BackStack_thenE3ClosesRightPane() {
        val detailRepo = ProbeThreadDetailRepository()
        val listRepo = ProbeThreadListRepository()
        val searchRepo = ProbeSearchRepository()
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(listRepo, searchRepo, settingsRepo, authRepo, gateway)

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 打开第 1 篇帖子，点击进度胶囊跳“末页”压入 PositionStack
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        composeRule.waitForIdle()
        assertNotNull(hostVm.state.value.selectedThread)

        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 10 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 第一次系统返回：E4 PositionStack 优先拦截，回第 1 页且右栏仍开着 1001
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNotNull("PositionStack 出栈不应关闭 E3 右栏", hostVm.state.value.selectedThread)
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 第二次系统返回：PositionStack 已空，交由 E3 多栏返回栈关闭右栏回到占位
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNull("栈空后再返回应由 E3 关闭右栏", hostVm.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }

    @Test
    fun probeA3_samePageJumpToFloor_duringInFlightPrependAndAppend_cancelsJobsAndUnblocksSubsequentPaging() {
        val repo = ProbeThreadDetailRepository()
        val auth = ProbeAuthRepository(signedInAccount())
        val vm = ThreadDetailViewModel(repo, createOfflineGateway(), auth, ProbeFavoriteRepository(), ProbeSettingsRepository())
        composeRule.setContent {
            // WhileSubscribed(5s) 需要活跃订阅方以驱动状态合流
            vm.uiState.collectAsStateWithLifecycle()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.bindArgs(ThreadNavArgs("8002")) }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(100L)
        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(100L)
        assertEquals(2, vm.uiState.value.page)

        val prependGate = CompletableDeferred<Unit>()
        val appendGate = CompletableDeferred<Unit>()
        repo.pageDelays["8002" to 1] = prependGate
        repo.pageDelays["8002" to 3] = appendGate

        composeRule.runOnIdle {
            vm.loadPreviousPage()
            vm.loadNextPage()
        }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(50L)
        assertTrue(vm.uiState.value.isPrepending)
        assertTrue(vm.uiState.value.isAppending)

        // 本页跳楼：必须 cancelPagingJobs() 并立即复位 isPrepending/isAppending
        composeRule.runOnIdle { vm.jumpToFloor("p208") }
        composeRule.waitForIdle()
        assertFalse("R-E2c S1-R2: isPrepending 必须立刻复位", vm.uiState.value.isPrepending)
        assertFalse("R-E2c S1-R2: isAppending 必须立刻复位", vm.uiState.value.isAppending)

        // 放行旧在途请求（应被 generation 丢弃，不脏改区间）
        prependGate.complete(Unit)
        appendGate.complete(Unit)
        advanceVirtualAndMainLooper(100L)
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)

        // 之后连续加载第 3 页与第 1 页必须仍能正常触发并合入
        repo.pageDelays.clear()
        composeRule.runOnIdle {
            vm.loadNextPage()
            vm.loadPreviousPage()
        }
        composeRule.waitForIdle()
        advanceVirtualAndMainLooper(100L)
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(3, vm.uiState.value.lastLoadedPage)
    }

    // -------------------------------------------------------------------------
    // 探针 b：suppressBottomGlass 整块卸载 HiGlassRegion 与三栏满配玻璃预算
    // -------------------------------------------------------------------------

    @Test
    fun probeB1_suppressBottomGlass_unmountsEntireHiGlassRegionAndReleasesSourceDemand() {
        var suppressStandalone by mutableStateOf(false)
        val standaloneGlassState = mutableStateOf<net.jejer.hipda.ui.designsystem.glass.HiGlassState?>(null)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState().also { standaloneGlassState.value = it }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().hiGlassSource(gs))
                        ThreadBottomToolbar(
                            page = 2,
                            lastPage = 8,
                            isFavorited = false,
                            isAttention = false,
                            glassState = gs,
                            suppressBottomGlass = suppressStandalone,
                            isJumpBarExpanded = false,
                            onExpandedChange = {},
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 独立工具条常态：仅 1 个 HiGlassMarker、hasSourceDemand == true、3 个 DrawBackdrop
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(standaloneGlassState.value!!.hasSourceDemand)
        assertEquals(3, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))

        // 2. suppressBottomGlass = true：整块卸载外层 HiGlassRegion，HiGlassMarker == 0 且 hasSourceDemand == false
        composeRule.runOnIdle { suppressStandalone = true }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse("卸载工具条后玻璃采样源需求必须释放为 false", standaloneGlassState.value!!.hasSourceDemand)
        assertEquals(0, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))

        // 3. 切回 false：恢复 1 个 HiGlassMarker 与采样需求
        composeRule.runOnIdle { suppressStandalone = false }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(standaloneGlassState.value!!.hasSourceDemand)
    }

    @Test
    fun probeB1b_threadDetailPane_suppressBottomGlass_togglesToolbarRegionCleanly() {
        val repo = ProbeThreadDetailRepository()
        val vm = ThreadDetailViewModel(repo, createOfflineGateway(), ProbeAuthRepository(signedInAccount()), ProbeFavoriteRepository(), ProbeSettingsRepository())
        var suppress by mutableStateOf(false)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    ThreadDetailPane(
                        args = ThreadNavArgs("8003"),
                        chromeSpec = DetailChromeSpec(showBack = true, suppressBottomGlass = suppress),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(2, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(4, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))

        composeRule.runOnIdle { suppress = true }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertDoesNotExist()

        composeRule.runOnIdle { suppress = false }
        composeRule.waitForIdle()
        assertEquals(2, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun probeB2_threePaneFullLoad_realE1E3E4_meetsExactGlassBudgetAndRightPaneBounds() {
        val detailRepo = ProbeThreadDetailRepository()
        val listRepo = ProbeThreadListRepository()
        val searchRepo = ProbeSearchRepository()
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(listRepo, searchRepo, settingsRepo, authRepo, gateway)

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
        )

        composeRule.setContent {
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        composeRule.waitForIdle()

        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("满配三栏全屏 HiGlassMarker 必须严格等于 3", 3, markers.size)

        val rootLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        val layerCount = countModifierName(rootLayout, "LayerBackdrop")
        val nestedCount = countNestedLayerBackdrops(rootLayout)
        val drawCount = countModifierName(rootLayout, "DrawBackdrop")
        assertTrue("独立 LayerBackdrop 采样源必须 ≤ 2，实际 $layerCount", layerCount <= 2)
        assertEquals("禁止任何嵌套 LayerBackdrop", 0, nestedCount)
        assertTrue("三栏静止 drawBackdrop pass 必须 ≤ 5，实际 $drawCount", drawCount <= 5)

        // 右栏底部工具条必须居中在右栏（不跨中右分隔线）
        val detailPaneBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文")).fetchSemanticsNode().boundsInRoot
        val progressBounds = composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").fetchSemanticsNode().boundsInRoot
        assertTrue("底部工具条左缘必须位于右栏内", progressBounds.left >= detailPaneBounds.left)
        assertTrue("底部工具条右缘必须位于右栏内", progressBounds.right <= detailPaneBounds.right)
    }

    // -------------------------------------------------------------------------
    // 探针 c：340dp + 1.25x 跳页、IME 零滚底、可打断/减动效、对比度、白名单与 MockWebServer 收藏撤销
    // -------------------------------------------------------------------------

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.NARROW_340_800, fontScale = 1.25f)
    fun probeC1_inlineJumpBar_340dpScale125_touchTargetsNoOverlap_sliderPreview_imeZeroBottomScroll_andAutoCollapse() {
        val repo = ProbeThreadDetailRepository()
        val vm = ThreadDetailViewModel(repo, createOfflineGateway(), ProbeAuthRepository(signedInAccount()), ProbeFavoriteRepository(), ProbeSettingsRepository())
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        var hostView: android.view.View? = null

        composeRule.setContent {
            hostView = LocalView.current
            scope = rememberCoroutineScope()
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs("8004"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 跳到第 3 页并滚到 p304 @ 53px，记录基线
        composeRule.runOnIdle { vm.loadPage(3) }
        composeRule.waitForIdle()
        val p304Idx = vm.uiState.value.floors.indexOfFirst { it.postId == "p304" }
        composeRule.runOnIdle { scope.launch { listState.scrollToItem(p304Idx, 53) } }
        composeRule.waitForIdle()

        val loadCallsBeforeExpand = repo.loadPageCalls.size
        val baselineItemIndex = listState.firstVisibleItemIndex
        val baselineScrollOffset = listState.firstVisibleItemScrollOffset

        // 1. 340dp + 1.25x 紧凑进度胶囊显示 3/10，点按原地展开双行跳页条
        composeRule.onNodeWithContentDescription("第 3 页，共 10 页，点按展开跳页").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val firstBtn = composeRule.onNodeWithText("首页").assertIsDisplayed().assertIsEnabled()
        val lastBtn = composeRule.onNodeWithText("末页").assertIsDisplayed().assertIsEnabled()
        val closeBtn = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        val previewNode = composeRule.onNodeWithText("第 3 / 10 页").assertIsDisplayed()
        val inputNode = composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        val sliderNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()

        // 全部控件 ≥ 48dp，双行无重叠不越出 340dp 容器
        firstBtn.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        lastBtn.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        closeBtn.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        inputNode.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        sliderNode.assertHeightIsAtLeast(48.dp)

        val firstBounds = firstBtn.fetchSemanticsNode().boundsInRoot
        val lastBounds = lastBtn.fetchSemanticsNode().boundsInRoot
        val previewBounds = previewNode.fetchSemanticsNode().boundsInRoot
        val sliderBounds = sliderNode.fetchSemanticsNode().boundsInRoot
        val inputBounds = inputNode.fetchSemanticsNode().boundsInRoot
        val closeBounds = closeBtn.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

        assertTrue("双行布局上行(首页/预览/末页)应位于下行(滑块/输入/关闭)上方", maxOf(firstBounds.bottom, lastBounds.bottom, previewBounds.bottom) <= minOf(sliderBounds.top, inputBounds.top, closeBounds.top) + 1f)
        assertTrue("滑块右缘不得压到输入框", sliderBounds.right <= inputBounds.left + 1f)
        assertTrue("关闭按钮右缘不得溢出 340dp 屏幕", closeBounds.right <= rootBounds.right + 1f)
        assertTrue("滑块在 340dp+1.25x 下仍须保持足够可拖宽", sliderBounds.width > 60f)

        // 2. 拖动滑块（避开外层 10dp focus 留白从轨道内部起拖）：仅本地预览 + 同步输入框 + 按钮变“跳转”，0 网络 0 滚屏
        sliderNode.performTouchInput {
            swipeRight(startX = width * 0.25f, endX = width * 0.85f)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("跳转").assertIsDisplayed()
        assertEquals("拖动滑块不得触发 loadPage", loadCallsBeforeExpand, repo.loadPageCalls.size)
        assertEquals(baselineItemIndex, listState.firstVisibleItemIndex)
        assertEquals(baselineScrollOffset, listState.firstVisibleItemScrollOffset)

        // 3. 注入底部 IME insets 上移跳页条，硬断言底层楼层 LazyColumn 像素偏移变化 == 0dp
        val beforeImeInputTop = inputNode.fetchSemanticsNode().boundsInRoot.top
        val imePx = with(composeRule.density) { 260.dp.roundToPx() }
        val imeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .build()
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), imeInsets)
        }
        composeRule.waitForIdle()

        val afterImeInputTop = inputNode.fetchSemanticsNode().boundsInRoot.top
        assertTrue("IME 注入后跳页条应显著上移", beforeImeInputTop - afterImeInputTop > imePx * 0.5f)
        assertEquals("IME 弹出时底层 LazyColumn 首项索引严禁改变", baselineItemIndex, listState.firstVisibleItemIndex)
        assertEquals("IME 弹出时底层 LazyColumn 像素偏移变化必须 == 0dp", baselineScrollOffset, listState.firstVisibleItemScrollOffset)

        // 清除 IME 后验证回落且仍 0dp 滚底
        val clearImeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build()
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), clearImeInsets)
        }
        composeRule.waitForIdle()
        assertEquals(baselineItemIndex, listState.firstVisibleItemIndex)
        assertEquals(baselineScrollOffset, listState.firstVisibleItemScrollOffset)

        // 4. 点“跳转”后自动收起；再次展开输入页码回车确认自动收起；首页/末页在首末页禁用
        composeRule.onNodeWithContentDescription("跳转").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").assertDoesNotExist()
        assertTrue(vm.uiState.value.page != 3)

        // 展开并输入 1 回车跳转（应自动收起且禁用首页）
        composeRule.onNodeWithContentDescription("第 ${vm.uiState.value.page} 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        composeRule.onNodeWithText("首页").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").assertIsNotEnabled()
        composeRule.onNodeWithText("末页").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(10, vm.uiState.value.page)
        composeRule.onNodeWithContentDescription("第 10 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()
        assertEquals(10, vm.uiState.value.page)
    }

    @Test
    fun probeC2_inlineJumpBar_midTransitionReversalIsContinuous_andReducedMotionCompletesIn1Frame() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().hiGlassSource(gs))
                        ThreadBottomToolbar(
                            page = 4,
                            lastPage = 12,
                            isFavorited = false,
                            isAttention = false,
                            glassState = gs,
                            suppressBottomGlass = false,
                            isJumpBarExpanded = expanded,
                            onExpandedChange = { expanded = it },
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 使用 assertFrameContinuity 采样“展开第 0 帧 → 第 4 帧中途反向收起”的逐帧连续性
        val result = composeRule.assertFrameContinuity(
            nodeProvider = {
                if (expanded) composeRule.onNodeWithText("第 4 / 12 页")
                else composeRule.onNodeWithContentDescription("第 4 页，共 12 页，点按展开跳页")
            },
            frameCount = 16,
            maxStepDp = 80.dp,
            minTotalTravelDp = 12.dp,
        ) { frame ->
            when (frame) {
                0 -> expanded = true
                4 -> expanded = false
            }
        }
        assertTrue("打断往返总位移应连续非零: ${result.totalTravelDp}", result.totalTravelDp >= 12.dp)
    }

    @Test
    fun probeC2b_reducedMotion_inlineJumpBarExpandsAndCollapsesInSingleFrame() = withAnimatorDurationScale(0f) {
        var expanded by mutableStateOf(false)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().hiGlassSource(gs))
                        ThreadBottomToolbar(
                            page = 4,
                            lastPage = 12,
                            isFavorited = false,
                            isAttention = false,
                            glassState = gs,
                            suppressBottomGlass = false,
                            isJumpBarExpanded = expanded,
                            onExpandedChange = { expanded = it },
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        waitForIdle()

        mainClock.autoAdvance = false
        try {
            expanded = true
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            onNodeWithText("首页").assertIsDisplayed()
            val fullWidth = onNodeWithText("首页").fetchSemanticsNode().boundsInRoot.left
            mainClock.advanceTimeByFrame()
            assertEquals("减动效展开应 1 帧到位，次帧位置不再变化", fullWidth, onNodeWithText("首页").fetchSemanticsNode().boundsInRoot.left, 0.5f)

            expanded = false
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            onNodeWithText("首页").assertDoesNotExist()
            onNodeWithContentDescription("第 4 页，共 12 页，点按展开跳页").assertIsDisplayed()
        } finally {
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun probeC3_progressCapsuleContrast_all20Presets_lightDarkOled_exceeds45To1() {
        val modes = listOf(
            Triple("Light", false, false),
            Triple("Dark", true, false),
            Triple("OLED", true, true),
        )
        HiColorPresets.ALL.forEach { preset ->
            modes.forEach { (modeLabel, dark, oled) ->
                val cs = hiColorSchemeFromSeed(preset.color, darkTheme = dark, pureBlack = oled)
                val colors = resolveProgressCapsuleColors(cs, darkTheme = dark)
                // 校验峰值 alpha 上限
                val maxAllowedAlpha = if (dark) HiGlassTokens.ProgressFillContainerDarkMaxAlpha else HiGlassTokens.ProgressFillContainerLightMaxAlpha
                assertTrue("${preset.id}/$modeLabel 进度填充 alpha=${colors.fillColor.alpha} 超上限 $maxAllowedAlpha", colors.fillColor.alpha <= maxAllowedAlpha + 0.0001f)

                // 1. 激活毛玻璃表面 + 标准背景（surface / surfaceContainer）上的进度填充可读性
                val activeSpec = resolveHiGlassStyleSpec(cs, darkTheme = dark, renderTier = net.jejer.hipda.ui.designsystem.glass.GlassRenderTier.REFRACTION_AND_BLUR, style = HiGlassStyle.Toolbar)
                listOf(cs.surface, cs.surfaceContainer).forEachIndexed { idx, baseBg ->
                    val glassComposite = activeSpec.activeTintColor.compositeOver(baseBg)
                    val filledSurface = colors.fillColor.compositeOver(glassComposite)
                    assertWcagContrastAtLeast(4.5f, glassComposite, colors.contentColor, "${preset.id}/$modeLabel/ActiveGlass/$idx")
                    assertWcagContrastAtLeast(4.5f, filledSurface, colors.contentColor, "${preset.id}/$modeLabel/ActiveProgressFill/$idx")
                }

                // 2. 94% 纯色降级表面在极端 #000000 / #FFFFFF 背景上的进度填充可读性
                val degradedSpec = resolveHiGlassStyleSpec(cs, darkTheme = dark, renderTier = net.jejer.hipda.ui.designsystem.glass.GlassRenderTier.SOLID_DEGRADED, style = HiGlassStyle.Toolbar)
                listOf(Color.Black, Color.White).forEach { extremeBackdrop ->
                    val degradedSurface = degradedSpec.degradedSurfaceColor.compositeOver(extremeBackdrop)
                    val filledDegraded = colors.fillColor.compositeOver(degradedSurface)
                    assertWcagContrastAtLeast(4.5f, filledDegraded, colors.contentColor, "${preset.id}/$modeLabel/DegradedProgress/$extremeBackdrop")
                }
            }
        }
    }

    @Test
    fun probeC4_externalUrlWhitelist_blocksDangerousSchemes_allowsHttps_andFavoriteAttentionUndoMockWebServer() {
        // 1. 纯白名单与工具函数反例验证
        val dangerousUrls = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:void(0)",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "file:///etc/passwd",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>alert(1)</script>",
            "mailto:test@example.com",
            "tel:10086",
            "vbscript:msgbox(1)",
            "https://www.4d4y.com/forum/\nviewthread.php?tid=1",
            "https://www.4d4y.com/forum/\rviewthread.php?tid=1",
            "https://www.4d4y.com/forum/\u0000viewthread.php?tid=1",
            "https:///forum/viewthread.php?tid=1",
            "https://user@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://user:pass@www.4d4y.com/forum/viewthread.php?tid=1",
            "http://",
        )
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startedIntents = mutableListOf<Intent>()
        dangerousUrls.forEach { bad ->
            assertNull("危险 URL 必须返回 null: $bad", sanitizeExternalHttpUrl(bad))
            assertFalse("openExternalHttpUrl 必须拒绝: $bad", openExternalHttpUrl(context, bad) { startedIntents.add(it) })
            assertFalse("shareExternalHttpUrl 必须拒绝: $bad", shareExternalHttpUrl(context, "标题", bad, "分享") { startedIntents.add(it) })
            assertFalse("copyThreadLinkPlainText 必须拒绝: $bad", copyThreadLinkPlainText(context, "链接", bad))
        }
        assertEquals("危险 URL 不得触发任何系统 Intent", 0, startedIntents.size)

        // 2. 启动离线 MockWebServer 并连接真实 DefaultFavoriteRepository 验证收藏/关注与撤销闭环 + UI 菜单外链白名单
        val server = MockWebServer()
        val requestPaths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                requestPaths.add(target)
                return when {
                    target.contains("item=favorites") && target.contains("action=add") ->
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[主题已成功添加到收藏夹中]]></root>""")
                    target.contains("item=favorites") && target.contains("action=remove") ->
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[已将此主题从您的收藏夹中移除]]></root>""")
                    target.contains("item=attention") && target.contains("action=add") ->
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[已将此主题加入到您的关注列表中]]></root>""")
                    target.contains("item=attention") && target.contains("action=remove") ->
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[已将此主题从您的关注列表中移除]]></root>""")
                    else -> MockResponse.Builder().code(404).body("Not found").build()
                }
            }
        }
        server.start()
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val okHttp = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 Chrome/131.0.0.0 Safari/537.36" },
            transportInterceptor = TransportInterceptor({ null }, { false }),
        )
        try {
            val serverUrl = server.url("").toString().trimEnd('/')
            val settingsRepo = ProbeSettingsRepository(AppSettings(forumServer = serverUrl))
            val authRepo = ProbeAuthRepository(signedInAccount())
            val gateway = SiteAccessGateway(okHttp, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val forumHttp = ForumHttpClient(gateway, settingsRepo, authRepo)
            val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = repoScope,
                produceFile = { File(tempFolder.newFolder(), "fav_probe.preferences_pb") },
            )
            val favRepo = DefaultFavoriteRepository(forumHttp, authRepo, FavoriteCacheStore(dataStore), System::currentTimeMillis, repoScope)
            val detailRepo = ProbeThreadDetailRepository()
            val vm = ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo)
            val uriHandlerCalls = mutableListOf<String>()
            val recordingUriHandler = object : UriHandler {
                override fun openUri(uri: String) {
                    uriHandlerCalls.add(uri)
                }
            }
            val snackbarHostState = SnackbarHostState()

            composeRule.setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(
                            LocalUriHandler provides recordingUriHandler,
                            LocalPaneSnackbarController provides SingleHostPaneSnackbarController(snackbarHostState),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("8005", title = "外链与收藏探针帖"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = vm,
                                )
                                SnackbarHost(
                                    snackbarHostState,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                                )
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // A. UI 层点击“在浏览器中打开”先注入危险 scheme 域名验证零触发
            val shadowApp = Shadows.shadowOf(context)
            while (shadowApp.nextStartedActivity != null) { /* drain */ }
            val badServers = listOf(
                "javascript:alert(1)",
                "intent://evil",
                "file:///etc/passwd",
                "content://settings/secure",
                "data:text/html,hi",
                "https://user@www.4d4y.com/forum",
                "https://user:pass@www.4d4y.com/forum",
            )
            for (badServer in badServers) {
                composeRule.runOnUiThread {
                    settingsRepo.settingsFlow.update { it.copy(forumServer = badServer) }
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("更多").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("在浏览器中打开").performClick()
                composeRule.waitForIdle()
                assertNull("危险 scheme $badServer 不得启动系统 Activity", shadowApp.nextStartedActivity)
                assertEquals("危险 scheme $badServer 不得触发 LocalUriHandler", 0, uriHandlerCalls.size)
            }

            // B. 恢复合法 https:// 域名并验证“在浏览器中打开”与“复制链接”
            composeRule.runOnUiThread {
                settingsRepo.settingsFlow.update { it.copy(forumServer = "https://www.4d4y.com/forum") }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("更多").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("在浏览器中打开").performClick()
            composeRule.waitForIdle()
            val openedIntent = shadowApp.nextStartedActivity
            assertNotNull("合法 https:// 必须放行启动浏览器 Intent", openedIntent)
            assertEquals(Intent.ACTION_VIEW, openedIntent.action)
            assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=8005", openedIntent.dataString)

            composeRule.onNodeWithContentDescription("更多").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("复制链接").performClick()
            composeRule.waitForIdle()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clipItem = clipboard?.primaryClip?.getItemAt(0)
            assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=8005", clipItem?.text?.toString())
            assertNull(clipItem?.htmlText)
            assertNull(clipItem?.uri)
            assertNull(clipItem?.intent)
            composeRule.onNodeWithText("链接已复制").assertIsDisplayed()

            // C. 切回 MockWebServer 地址测试真实离线收藏 + 撤销与关注 + 撤销
            composeRule.runOnUiThread {
                settingsRepo.settingsFlow.update { it.copy(forumServer = serverUrl) }
            }
            composeRule.waitForIdle()

            // 收藏 -> 已收藏 + 撤销 -> 点撤销恢复未收藏
            composeRule.onNodeWithContentDescription("收藏").performClick()
            waitUntilRealCondition(5_000L, {
                "收藏未完成: requestPaths=$requestPaths, favs=${favRepo.favoriteTids.value}, notice=${vm.uiState.value.notice}"
            }) {
                favRepo.isFavorite("8005") && composeRule.onAllNodesWithText("已收藏").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("撤销").performClick()
            waitUntilRealCondition(5_000L, {
                "撤销收藏未完成: requestPaths=$requestPaths, favs=${favRepo.favoriteTids.value}, notice=${vm.uiState.value.notice}"
            }) {
                !favRepo.isFavorite("8005")
            }
            assertFalse(vm.uiState.value.isFavorited)

            // 关注 -> 已关注 + 撤销 -> 点撤销恢复未关注
            composeRule.onNodeWithContentDescription("更多").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("关注").performClick()
            waitUntilRealCondition(5_000L, {
                "关注未完成: requestPaths=$requestPaths, atts=${favRepo.attentionTids.value}, notice=${vm.uiState.value.notice}"
            }) {
                favRepo.isAttention("8005") && composeRule.onAllNodesWithText("已关注").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("撤销").performClick()
            waitUntilRealCondition(5_000L, {
                "撤销关注未完成: requestPaths=$requestPaths, atts=${favRepo.attentionTids.value}, notice=${vm.uiState.value.notice}"
            }) {
                !favRepo.isAttention("8005")
            }
            assertFalse(vm.uiState.value.isAttention)

            assertTrue(requestPaths.any { it.contains("item=favorites") && it.contains("action=add") && it.contains("tid=8005") })
            assertTrue(requestPaths.any { it.contains("item=favorites") && it.contains("action=remove") && it.contains("tid=8005") })
            assertTrue(requestPaths.any { it.contains("item=attention") && it.contains("action=add") && it.contains("tid=8005") })
            assertTrue(requestPaths.any { it.contains("item=attention") && it.contains("action=remove") && it.contains("tid=8005") })
        } finally {
            repoScope.cancel()
            okHttp.dispatcher.cancelAll()
            okHttp.dispatcher.executorService.shutdownNow()
            okHttp.connectionPool.evictAll()
            server.close()
        }
    }

    // -------------------------------------------------------------------------
    // R-C5b 收口回归探针：HiRollingNumber 无二次重组 + HiPressScale detach 复位
    // -------------------------------------------------------------------------

    @Test
    fun probeRC5b_rollingNumberDigitUpdate_andPressFeedbackDetachCleanup() {
        var pageNum by mutableIntStateOf(9)
        var showButton by mutableStateOf(true)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.fillMaxSize()) {
                    HiRollingNumber(value = pageNum)
                    if (showButton) {
                        Button(onClick = {}) { Text("按钮") }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { pageNum = 10 }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showButton = false }
        composeRule.waitForIdle()
    }

    // -------------------------------------------------------------------------
    // 负责人追加合并态集成探针 (d)(e)：与 origin/main (E-slots/E8/E10/E12) 联调验证
    // -------------------------------------------------------------------------

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probeD1_mergedThreePane_imageViewerAccountAndMessagesDrilldownOverlays_preserveToolbarAndPositionStack() {
        val imageBlock = ContentBlock.Image(
            fullUrl = "https://www.4d4y.com/forum/attachments/probe_d1.jpg",
            thumbUrl = "https://www.4d4y.com/forum/attachments/probe_d1_t.jpg",
            fileSizeBytes = 2048L,
        )
        val detailRepo = ProbeThreadDetailRepository(
            imagePages = mapOf(("1001" to 1) to imageBlock),
        ).apply {
            preSavedPositions["1001"] = ReadingPositionEntity(
                tid = "1001",
                page = 5,
                pid = "p503",
                offsetPx = 120,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
        }
        val listRepo = ProbeThreadListRepository()
        val searchRepo = ProbeSearchRepository()
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(listRepo, searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        var overlayViewerOpen = false

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = object : ImageViewerRenderer {
                            @Composable
                            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                                overlayViewerOpen = true
                                BackHandler(onBack = onDismiss)
                                Box(Modifier.fillMaxSize()) {
                                    Text("看图浮层预览:${args.urls.firstOrNull().orEmpty()}")
                                }
                            }
                        },
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 从论坛中栏打开帖子 1001，等待唯一一次位置恢复落到第 5 页 p503
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals("首次打开帖子位置恢复门闩只能触发一次", 1, detailRepo.getReadingPositionCount("1001"))
        composeRule.onNodeWithContentDescription("第 5 页，共 10 页，点按展开跳页").assertIsDisplayed()

        val detailVm = ViewModelProvider(
            hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        assertEquals(5, detailVm.uiState.value.page)
        composeRule.runOnIdle { detailVm.onReadingPositionChanged(5, "p506", 30) }
        advanceVirtualAndMainLooper(500L)

        // 跳去首页建立非空 PositionStack
        composeRule.onNodeWithContentDescription("第 5 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").performClick()
        advanceVirtualAndMainLooper(200L)
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        val toolbarBeforeOverlay = composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()
        assertNotNull(toolbarBeforeOverlay)

        // 2. 点击第 1 页 1 楼图片打开 E-slots 看图浮层：工具条与位置栈不得误卸载，返回只关看图浮层
        waitUntilRealCondition(5_000L, { "楼层图片未进入可点击已加载状态" }) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))[0].performClick()
        composeRule.waitForIdle()
        assertTrue(overlayViewerOpen)
        composeRule.onNodeWithText("看图浮层预览", substring = true).assertIsDisplayed()
        assertEquals("打开看图浮层期间不得误触发二次水合", 1, detailRepo.getReadingPositionCount("1001"))
        assertEquals(1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("看图浮层预览", substring = true).assertDoesNotExist()
        assertEquals("关闭看图浮层后位置栈不得被连带出栈", 1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 3. 打开 E12 账号管理浮层（LoginOverlayMode.AccountMenu）：不卸载 E4 工具条，返回只关账号浮层
        composeRule.onNodeWithTag(HiShellTestTags.SidebarAccountRow).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前账号").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))
        assertEquals(1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(hostVm.state.value.loginOverlayMode)
        assertEquals("关闭账号浮层后位置栈仍应保持非空且停在第 1 页", 1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 4. 在右栏上层叠 E12 用户资料 -> E8 短消息对话下钻：返回逐级退出后 E4 仍保留原页与位置栈且绝不二次水合
        composeRule.onNodeWithContentDescription("楼主，楼主，2026-11-26 10:00")
            .performClick()
        advanceVirtualAndMainLooper(150L)
        composeRule.onNodeWithText("发短消息").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(150L)
        assertEquals(2, hostVm.state.value.detailDrilldownStack.size)
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, hostVm.state.value.detailDrilldownStack.size)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        advanceVirtualAndMainLooper(150L)
        assertTrue(hostVm.state.value.detailDrilldownStack.isEmpty())
        assertEquals("从消息/资料下钻返回帖子不得再次调用 getReadingPosition", 1, detailRepo.getReadingPositionCount("1001"))
        assertEquals(1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 5. 所有上层浮层与下钻均关闭后，下一次返回终于由 E4 PositionStack 接管回到第 5 页 p506
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        advanceVirtualAndMainLooper(200L)
        assertEquals(5, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithContentDescription("第 5 页，共 10 页，点按展开跳页").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))

        // 6. 栈空后再次返回交给 E3 多栏退出右栏回到空占位
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(hostVm.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w600dp-h800dp")
    fun probeD2_mergedModalDrawer_andSettingsSubPageOverlays_doNotConsumePositionStackOrRehydrate() {
        val detailRepo = ProbeThreadDetailRepository().apply {
            preSavedPositions["1001"] = ReadingPositionEntity(
                tid = "1001",
                page = 4,
                pid = "p402",
                offsetPx = 40,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
        }
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = object : ImageViewerRenderer {
                            @Composable
                            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                                BackHandler(onBack = onDismiss)
                                Box(Modifier.fillMaxSize()) { Text("单栏看图") }
                            }
                        },
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 打开帖子并建立 PositionStack（4 -> 首页 1）
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(250L)
        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").performClick()
        advanceVirtualAndMainLooper(150L)
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))

        val detailVm = ViewModelProvider(
            hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        assertEquals(1, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 1. 600dp 抽屉导航态：展开模态侧栏浮层（SidebarModalDrawer）覆盖在单栏正文之上 -> 返回仅关闭抽屉
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("关闭导航抽屉不得出栈 E4 PositionStack（抽屉未拦截导致底层退栈）", 1, detailVm.uiState.value.page)
        assertTrue("关闭导航抽屉时 E4 PositionStack 仍应保持非空", detailVm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))

        // 2. 抽屉关闭后 PositionStack 仍可回退第 4 页并再按返回离开正文
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        advanceVirtualAndMainLooper(200L)
        assertEquals(4, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").assertIsDisplayed()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(hostVm.state.value.selectedThread)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probeD3_mergedSettingsReleaseThreadAndAccountAndMessages_isolateOverlaysAndPositionStack() {
        val detailRepo = ProbeThreadDetailRepository().apply {
            // 设置 -> 关于 -> 客户端发布帖 (tid = 1579403 = CLIENT_RELEASE_TID) 预置恢复点，用于验证设置进入帖子 + 浮层叠加
            preSavedPositions["1579403"] = ReadingPositionEntity(
                tid = "1579403",
                page = 4,
                pid = "p402",
                offsetPx = 40,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
        }
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = null,
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 进入 我的 -> 设置 -> 论坛与发帖 -> 管理版块子页，验证子页 BackHandler 干净退回设置分组详情
        composeRule.onNodeWithText("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("论坛与发帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("管理版块").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已关注").assertIsDisplayed()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("显示置顶帖").assertIsDisplayed()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("关于").assertIsDisplayed()

        // 2. 进入 关于 -> 客户端发布帖 (1579403)：位置恢复一次落第 4 页，跳去首页入栈后叠加 E12 账号管理浮层再关闭
        composeRule.onNodeWithText("关于").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("客户端发布帖").performClick()
        advanceVirtualAndMainLooper(300L)
        assertEquals(1, detailRepo.getReadingPositionCount("1579403"))
        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").performClick()
        advanceVirtualAndMainLooper(150L)

        composeRule.runOnIdle { hostVm.openLoginOverlay(LoginOverlayMode.AccountMenu) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前账号").assertIsDisplayed()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(hostVm.state.value.loginOverlayMode)
        assertEquals(1, detailRepo.getReadingPositionCount("1579403"))
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 3. 弹出 PositionStack 回第 4 页，再返回退回 设置 -> 关于，不再次触发水合
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        advanceVirtualAndMainLooper(200L)
        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").assertIsDisplayed()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("客户端发布帖").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("1579403"))

        // 4. 退到 我的 主菜单后切换 消息 验证 E8 短消息列表正常加载
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() } // 关于 -> 设置分组
        composeRule.waitForIdle()
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() } // 设置分组 -> 我的主菜单
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息探针用户").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probeE_enterFromM6Pages_searchResults_searchLink_favorites_notifyLinks_hydratesOnceAndReturnsCleanly() {
        val searchItems = listOf(
            SearchItem("9101", null, "搜索命中帖 9101", "摘要内容", "楼主", "100", null, "今天", "Discovery"),
        )
        val favoriteItems = listOf(
            FavoriteThreadItem("9201", "我的收藏探针帖 9201", "2026-11-26", "Discovery"),
        )
        val notifyEntries = listOf(
            NotifyEntry(
                key = "n1",
                item = NotifyItem(NotifyKind.REPLY, "提醒回复帖 9301", "回复了你的主题", "坛友A", "201", null, "9301", null, "刚刚", true),
                isRead = false,
            ),
            NotifyEntry(
                key = "n2",
                item = NotifyItem(NotifyKind.QUOTE, "提醒引用楼层帖 9302", "引用了第2页楼层", "坛友B", "202", null, "9302", "p205", "1分钟前", true),
                isRead = false,
            ),
        )

        val detailRepo = ProbeThreadDetailRepository().apply {
            preSavedPositions["9101"] = ReadingPositionEntity("9101", 4, "p406", 90, ReadingFilterMode.NONE, updatedAt = 100L)
            preSavedPositions["9102"] = ReadingPositionEntity("9102", 3, "p304", 75, ReadingFilterMode.NONE, updatedAt = 100L)
            preSavedPositions["9201"] = ReadingPositionEntity("9201", 6, "p608", 110, ReadingFilterMode.NONE, updatedAt = 100L)
            preSavedPositions["9301"] = ReadingPositionEntity("9301", 7, "p704", 80, ReadingFilterMode.NONE, updatedAt = 100L)
            preSavedPositions["9302"] = ReadingPositionEntity("9302", 5, "p503", 60, ReadingFilterMode.NONE, updatedAt = 100L)
        }
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository(favoriteItems = favoriteItems)
        val searchRepo = ProbeSearchRepository(searchResults = searchItems)
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository(notifyEntries = notifyEntries)
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()

        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = null,
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. M6 搜索结果页 -> 进入帖子 9101：位置恢复门闩仅触发一次且落到第 4 页 p406，不展示首楼 p401
        composeRule.onNodeWithText("搜索").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("探针检索")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        advanceVirtualAndMainLooper(150L)

        composeRule.onNodeWithText("搜索命中帖 9101").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals("搜索结果进入应仅调用 1 次 getReadingPosition", 1, detailRepo.getReadingPositionCount("9101"))
        composeRule.onNodeWithContentDescription("第 4 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p406", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("p401", substring = true).assertDoesNotExist()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("搜索命中帖 9101").assertIsDisplayed()
        assertEquals("从搜索帖子返回不得再次水合", 1, detailRepo.getReadingPositionCount("9101"))

        // 2. 搜索框站内帖子链接直达 9102 -> 门闩仅触发一次且落到第 3 页 p304，返回仍回搜索页
        composeRule.onNode(hasSetTextAction()).performTextReplacement("https://www.4d4y.com/forum/viewthread.php?tid=9102")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开这个链接").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals(1, detailRepo.getReadingPositionCount("9102"))
        composeRule.onNodeWithContentDescription("第 3 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p304", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("p301", substring = true).assertDoesNotExist()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开这个链接").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("9102"))

        // 3. M6「我的 -> 收藏与关注」进入 9201 -> 门闩仅触发一次且落到第 6 页 p608，返回回收藏列表
        composeRule.onNodeWithText("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("收藏与关注").performClick()
        advanceVirtualAndMainLooper(150L)

        composeRule.onNodeWithText("我的收藏探针帖 9201").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals("收藏列表进入应仅调用 1 次 getReadingPosition", 1, detailRepo.getReadingPositionCount("9201"))
        composeRule.onNodeWithContentDescription("第 6 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p608", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("p601", substring = true).assertDoesNotExist()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("我的收藏探针帖 9201").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("9201"))

        // 二级子页“收藏与关注”按返回回到“我的”主菜单，底栏导航重新出现后再切“消息”
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        // 4a. M6「消息 -> 提醒」无目标 pid 帖子链接（tid=9301）：仅触发 1 次恢复门闩且落第 7 页 p704，返回回提醒列表
        composeRule.onNodeWithText("消息").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提醒").performClick()
        advanceVirtualAndMainLooper(150L)

        composeRule.onNodeWithText("提醒回复帖 9301").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals("消息提醒帖进入应仅调用 1 次 getReadingPosition", 1, detailRepo.getReadingPositionCount("9301"))
        composeRule.onNodeWithContentDescription("第 7 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p704", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("p701", substring = true).assertDoesNotExist()

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提醒回复帖 9301").assertIsDisplayed()
        assertEquals(1, detailRepo.getReadingPositionCount("9301"))

        // 4b. M6「消息 -> 提醒」带明确 targetPid（tid=9302, pid=p205）：直达指定楼层 p205（第 2 页），不误冲写原记录并干净返回
        composeRule.onNodeWithText("提醒引用楼层帖 9302").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        assertEquals("带 targetPid 提醒深链不应走上次阅读位置恢复", 0, detailRepo.getReadingPositionCount("9302"))
        composeRule.onNodeWithContentDescription("第 2 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p205", substring = true).assertIsDisplayed()
        assertEquals(0, detailRepo.saveCalls.count { it.tid == "9302" })

        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提醒引用楼层帖 9302").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 第 2 轮复验新增探针 (f)(g)：停手 <400ms 立即返回/三栏切帖可挂起持久化 & E14 看图 0 玻璃协同
    // -------------------------------------------------------------------------

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probeF1_under400msStopScroll_immediateBack_suspendingSaveSurvivesCancellationAndRestores() {
        val detailRepo = ProbeThreadDetailRepository().apply {
            preSavedPositions["1001"] = ReadingPositionEntity(
                tid = "1001",
                page = 5,
                pid = "p502",
                offsetPx = 20,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
        }
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = null,
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子 1001，等待首轮水合完成，确认停在第 5 页 p502 且未误触发写盘
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 5 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p502", substring = true).assertIsDisplayed()
        assertEquals(0, detailRepo.saveStartedCalls.size)
        assertEquals(0, detailRepo.saveCalls.size)

        val vm1001 = ViewModelProvider(
            hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        val vmScopeJob = vm1001.viewModelScope.coroutineContext[Job]!!

        // 2. 真实手指滚屏后 <400ms 停手，先硬断言防抖尚未触发 saveReadingPosition
        scrollDetailDownWithinDebounce("1001")
        advanceVirtualAndMainLooper(50L)
        assertEquals("停手 <400ms 内去抖窗口不得提前写盘", 0, detailRepo.saveStartedCalls.size)

        // 3. 挂载会真实挂起的写入闸门，并立即按系统返回退出帖子（触发 onDispose + onCleared 取消 viewModelScope）
        val writeGate = CompletableDeferred<Unit>()
        detailRepo.saveDelaysByTid["1001"] = writeGate
        composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
        advanceUntilVirtualCondition(2_500L) {
            detailRepo.saveStartedCalls.any { it.tid == "1001" } && !vmScopeJob.isActive
        }

        assertFalse("立即返回并退场后旧帖子 viewModelScope 必须已取消", vmScopeJob.isActive)
        assertEquals("写入闸门未放行前 saveCalls 必须仍为 0（真实挂起）", 0, detailRepo.saveCalls.count { it.tid == "1001" })
        val inFlightWrite = detailRepo.saveStartedCalls.last { it.tid == "1001" }
        assertEquals(5, inFlightWrite.page)
        assertTrue(
            "刷盘携带的必须是停手前的最新滚动位置，而非旧位置 p502@20: $inFlightWrite",
            inFlightWrite.pid != "p502" || inFlightWrite.offsetPx != 20,
        )

        // 4. 放行真实挂起写入：即使 viewModelScope 已取消仍须在 NonCancellable 中完整写盘，再重进精准恢复
        detailRepo.saveDelaysByTid.remove("1001")
        writeGate.complete(Unit)
        waitUntilRealCondition(3_000L, { "挂起写入放行后未落盘: calls=${detailRepo.saveCalls}" }) {
            detailRepo.saveCalls.any { it.tid == "1001" }
        }
        assertEquals(inFlightWrite, detailRepo.saveCalls.last { it.tid == "1001" })
        val persisted = detailRepo.preSavedPositions["1001"]!!
        assertEquals(inFlightWrite.page, persisted.page)
        assertEquals(inFlightWrite.pid, persisted.pid)
        assertEquals(inFlightWrite.offsetPx, persisted.offsetPx)

        composeRule.onNodeWithText("探针论坛帖子 1001").assertIsDisplayed().performClick()
        advanceVirtualAndMainLooper(350L)
        composeRule.waitForIdle()
        assertTrue(
            "重进 1001 后视口应恢复挂载目标楼层 ${inFlightWrite.pid}",
            composeRule.onAllNodesWithText(inFlightWrite.pid, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText("p501", substring = true).assertDoesNotExist()
        assertEquals(2, detailRepo.getReadingPositionCount("1001"))
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probeF2_threePane_immediateThreadSwitch_under400msAndOverlappingSlowWritePersistLatestCleanly() {
        val detailRepo = ProbeThreadDetailRepository().apply {
            preSavedPositions["1001"] = ReadingPositionEntity(
                tid = "1001",
                page = 5,
                pid = "p502",
                offsetPx = 20,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
            preSavedPositions["1002"] = ReadingPositionEntity(
                tid = "1002",
                page = 3,
                pid = "p304",
                offsetPx = 44,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
            preSavedPositions["1003"] = ReadingPositionEntity(
                tid = "1003",
                page = 2,
                pid = "p202",
                offsetPx = 16,
                filterMode = ReadingFilterMode.NONE,
                updatedAt = 100L,
            )
        }
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )

        composeRule.setContent {
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = null,
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Part A: 打开 1001 滚屏后 <400ms 立即在三栏中栏切换至 1002，挂起写入在旧 VM 销毁后仍必须落盘
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("p502", substring = true).assertIsDisplayed()

        val vm1001First = ViewModelProvider(
            hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        val vmFirstJob = vm1001First.viewModelScope.coroutineContext[Job]!!

        scrollDetailDownWithinDebounce("1001")
        advanceVirtualAndMainLooper(50L)
        assertEquals(0, detailRepo.saveStartedCalls.size)

        val switchGate = CompletableDeferred<Unit>()
        detailRepo.saveDelaysByTid["1001"] = switchGate
        composeRule.onNodeWithText("探针论坛帖子 1002").performClick()
        advanceUntilVirtualCondition(2_500L) {
            detailRepo.saveStartedCalls.any { it.tid == "1001" } && !vmFirstJob.isActive
        }

        assertFalse("三栏切帖退场后 1001 viewModelScope 必须已取消", vmFirstJob.isActive)
        assertEquals(0, detailRepo.saveCalls.count { it.tid == "1001" })
        composeRule.onNodeWithContentDescription("第 3 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.onNodeWithText("p304", substring = true).assertIsDisplayed()
        val firstSwitchWrite = detailRepo.saveStartedCalls.last { it.tid == "1001" }
        assertEquals(5, firstSwitchWrite.page)
        assertTrue(firstSwitchWrite.pid != "p502" || firstSwitchWrite.offsetPx != 20)

        detailRepo.saveDelaysByTid.remove("1001")
        switchGate.complete(Unit)
        waitUntilRealCondition(3_000L, { "三栏切帖挂起写入放行后未落盘: ${detailRepo.saveCalls}" }) {
            detailRepo.saveCalls.any { it.tid == "1001" }
        }
        assertEquals(firstSwitchWrite, detailRepo.saveCalls.last { it.tid == "1001" })

        // Part B: 重进 1001 后触发一次慢写入并挂起，挂起期间再滚动并在 <400ms 内切去 1003，验证串行交接且最终落最新位
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(350L)
        composeRule.waitForIdle()
        assertTrue(
            "重进 1001 后视口应恢复挂载目标楼层 ${firstSwitchWrite.pid}",
            composeRule.onAllNodesWithText(firstSwitchWrite.pid, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )

        val vm1001Second = ViewModelProvider(
            hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        val vmSecondJob = vm1001Second.viewModelScope.coroutineContext[Job]!!
        val startIdx = detailRepo.saveStartedCalls.size
        val savedIdx = detailRepo.saveCalls.size

        val slowGate1 = CompletableDeferred<Unit>()
        detailRepo.saveDelaysByTid["1001"] = slowGate1
        scrollDetailDownWithinDebounce("1001")
        advanceVirtualAndMainLooper(500L)
        waitUntilRealCondition(2_000L, { "首次慢写入未进入 saveReadingPosition" }) {
            detailRepo.saveStartedCalls.drop(startIdx).count { it.tid == "1001" } == 1
        }
        val midWrite1 = detailRepo.saveStartedCalls.last { it.tid == "1001" }
        assertEquals(5, midWrite1.page)
        assertEquals(0, detailRepo.saveCalls.drop(savedIdx).count { it.tid == "1001" })

        // 在第 1 次写入仍挂起时继续滚动并 <400ms 立即切到 1003
        scrollDetailDownWithinDebounce("1001")
        advanceVirtualAndMainLooper(50L)
        composeRule.onNodeWithText("探针论坛帖子 1003").performClick()
        advanceUntilVirtualCondition(2_500L) { !vmSecondJob.isActive }
        assertFalse(vmSecondJob.isActive)
        assertEquals("旧写入放行前，新老写入均不得越序提前落盘", 0, detailRepo.saveCalls.drop(savedIdx).count { it.tid == "1001" })

        detailRepo.saveDelaysByTid.remove("1001")
        slowGate1.complete(Unit)
        waitUntilRealCondition(3_000L, { "串行双写放行后未全部落盘: calls=${detailRepo.saveCalls}" }) {
            detailRepo.saveCalls.drop(savedIdx).count { it.tid == "1001" } >= 2
        }
        val completedPair = detailRepo.saveCalls.drop(savedIdx).filter { it.tid == "1001" }
        assertEquals(2, completedPair.size)
        assertEquals("先前慢写入必须先完成", midWrite1, completedPair[0])
        assertEquals(5, completedPair[1].page)
        assertTrue(
            "后续 <400ms 补写必须在旧写之后落盘并推进位置: ${completedPair[1]} vs $midWrite1",
            completedPair[1].pid != midWrite1.pid || completedPair[1].offsetPx != midWrite1.offsetPx,
        )
        assertEquals(completedPair[1].pid, detailRepo.preSavedPositions["1001"]?.pid)
        assertEquals(completedPair[1].offsetPx, detailRepo.preSavedPositions["1001"]?.offsetPx)
        composeRule.onNodeWithText("p202", substring = true).assertIsDisplayed()
    }

    @Test
    fun probeG1_localHiGlassSuspended_pausesBottomToolbarGlassAndSourceDemandCleanly() {
        var suspendStandalone by mutableStateOf(false)
        val standaloneGlass = mutableStateOf<net.jejer.hipda.ui.designsystem.glass.HiGlassState?>(null)
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState().also { standaloneGlass.value = it }
                    CompositionLocalProvider(LocalHiGlassSuspended provides suspendStandalone) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().hiGlassSource(gs))
                            ThreadBottomToolbar(
                                page = 2,
                                lastPage = 8,
                                isFavorited = false,
                                isAttention = false,
                                glassState = gs,
                                suppressBottomGlass = false,
                                isJumpBarExpanded = false,
                                onExpandedChange = {},
                                onReplyClick = {},
                                onJumpToPage = {},
                                onToggleFavorite = {},
                                onRefreshClick = {},
                                onToggleAttention = {},
                                onCopyLinkClick = {},
                                onOpenInBrowserClick = {},
                                onShareClick = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))
        assertTrue(standaloneGlass.value!!.hasSourceDemand)

        composeRule.runOnIdle { suspendStandalone = true }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, false), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))
        assertEquals(0, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "LayerBackdrop"))
        assertFalse(standaloneGlass.value!!.hasSourceDemand)

        composeRule.runOnIdle { suspendStandalone = false }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"))
        assertTrue(standaloneGlass.value!!.hasSourceDemand)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probeG2_mergedE14RealImageViewer_suspendsBottomToolbarAndTopBarGlassToZero_andRestoresOnDismiss() {
        val imageBlock = ContentBlock.Image(
            fullUrl = "https://www.4d4y.com/forum/attachments/probe_g_full.jpg",
            thumbUrl = "https://www.4d4y.com/forum/attachments/probe_g_thumb.jpg",
            fileSizeBytes = 4096L,
        )
        val detailRepo = ProbeThreadDetailRepository(
            imagePages = mapOf(("1001" to 1) to imageBlock),
        )
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val offlineImageLoader = createOfflineImageLoader()
        val realE14Renderer = ImageViewerRendererImpl(
            originalDownloader = object : OriginalImageDownloader {
                override fun download(url: String) = emptyFlow<OriginalDownloadState>()
            },
            mediaActions = object : ImageViewerMediaActions {
                override suspend fun saveImage(url: String, cachedFile: File?) = false
                override suspend fun shareImage(url: String, cachedFile: File?) = false
            },
        )
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo) },
        )

        composeRule.setContent {
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = MutableStateFlow(0),
                        notifyUnreadCountFlow = MutableStateFlow(0),
                        imageLoader = offlineImageLoader,
                        loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                        messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway),
                        searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                        settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                            ApplicationProvider.getApplicationContext(),
                            settingsRepo,
                            ProbeNotificationScheduler(),
                            offlineImageLoader,
                            authRepo,
                            ProbeBlacklistRepository(),
                            ProbeUserRepository(),
                        ),
                        imageViewerRenderer = realE14Renderer,
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子 1001，确认看图前满配三栏共有 3 处 HiGlassMarker（含底部工具条）与活跃模糊采样
        composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
        advanceVirtualAndMainLooper(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        assertEquals(3, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        val activeBlurBefore = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("看图前应含顶栏+底栏共 ≥5 个激活模糊表面，实际 $activeBlurBefore", activeBlurBefore >= 5)
        assertTrue(countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop") >= 3)
        assertTrue(countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "LayerBackdrop") in 1..2)

        // 2. 点击楼层图片打开真实 E14 ImageViewerOverlay，等待 Settled 后验证同屏 0 玻璃 + 0 离屏采样
        waitUntilRealCondition(5_000L, { "楼层图片未加载就绪" }) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))[0].performClick()
        advanceUntilVirtualCondition(3_000L) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodes(hasContentDescription("关闭")).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(
            "打开真实 E14 看图浮层时同屏 HiGlassMarker 必须严格为 0（底部工具条与顶栏全部暂停）",
            0,
            composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            "打开真实 E14 看图浮层时激活模糊表面 HiGlassBlurActive==true 必须为 0",
            0,
            composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertTrue(
            "底层玻璃表面应降级保留但模糊关闭（HiGlassBlurActive==false）",
            composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, false), useUnmergedTree = true).fetchSemanticsNodes().size >= 5,
        )
        assertEquals(
            "打开真实 E14 看图时全屏 DrawBackdrop 必须严格为 0",
            0,
            countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop"),
        )
        assertEquals(
            "打开真实 E14 看图时全屏 LayerBackdrop 采样源必须释放为 0",
            0,
            countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "LayerBackdrop"),
        )
        assertEquals(1, detailRepo.getReadingPositionCount("1001"))

        // 3. 点击 E14 看图“关闭”按钮完成退场动画，硬断言底部工具条与顶栏 3 处玻璃完整恢复且不二次水合
        composeRule.onNodeWithContentDescription("关闭").performClick()
        advanceUntilVirtualCondition(3_000L) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        assertEquals(1, detailRepo.getReadingPositionCount("1001"))
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        assertEquals(3, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(
            activeBlurBefore,
            composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertTrue(countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "DrawBackdrop") >= 3)
        assertTrue(countModifierName(composeRule.onRoot().fetchSemanticsNode().layoutInfo, "LayerBackdrop") in 1..2)
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 离线测试替身与辅助函数
    // -------------------------------------------------------------------------

    private fun advanceVirtualAndMainLooper(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun advanceUntilVirtualCondition(
        timeoutMs: Long,
        stepMs: Long = 50L,
        predicate: () -> Boolean,
    ) {
        var elapsed = 0L
        composeRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        while (elapsed < timeoutMs && !predicate()) {
            advanceVirtualAndMainLooper(stepMs)
            composeRule.waitForIdle()
            elapsed += stepMs
        }
        assertTrue("虚拟时钟 ${timeoutMs}ms 内未满足条件", predicate())
    }

    private fun scrollDetailDownWithinDebounce(tid: String) {
        val listNode = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyDescendant(hasText("帖子 $tid 第", substring = true)),
        )
        listNode.performTouchInput {
            val midX = width * 0.5f
            val startY = height * 0.72f
            down(Offset(midX, startY))
            moveBy(Offset(0f, -90f), delayMillis = 16L)
            moveBy(Offset(0f, -90f), delayMillis = 16L)
            advanceEventTime(150L)
            moveBy(Offset.Zero)
            up()
        }
    }

    private fun countModifierName(root: LayoutInfo, needle: String): Int {
        var hits = 0
        fun walk(node: LayoutInfo) {
            hits += node.getModifierInfo().count { it.modifier.javaClass.name.contains(needle, ignoreCase = true) }
            childrenOf(node).forEach(::walk)
        }
        walk(root)
        return hits
    }

    private fun countNestedLayerBackdrops(root: LayoutInfo): Int {
        var nested = 0
        fun walk(node: LayoutInfo, inside: Boolean) {
            val hasLayer = node.getModifierInfo().any { it.modifier.javaClass.name.contains("LayerBackdrop", ignoreCase = true) }
            if (hasLayer && inside) nested += 1
            childrenOf(node).forEach { walk(it, inside || hasLayer) }
        }
        walk(root, inside = false)
        return nested
    }

    private fun childrenOf(node: LayoutInfo): List<LayoutInfo> {
        val getter = node.javaClass.methods.firstOrNull {
            (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
        }
        return (getter?.invoke(node) as? List<*>).orEmpty().filterIsInstance<LayoutInfo>()
    }

    private fun createOfflineGateway() = SiteAccessGateway(
        OkHttpClient(),
        ChallengeDetector(),
        ChallengeSolver { SolveResult.Cancelled },
    )

    private fun signedInAccount() = SavedAccount(
        uid = "42",
        username = "HiProbeUser",
        passwordMd5 = "0".repeat(32),
        lastLoginAt = 1L,
    )

    private fun gbkXmlResponse(xml: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/xml; charset=gbk")
        .body(Buffer().write(xml.toByteArray(Charset.forName("GB18030"))))
        .build()

    private class ProbeViewModelStoreOwner(
        private val hostVm: NextAppHostViewModel,
        private val forumVm: ForumViewModel,
        private val detailVmFactory: () -> ThreadDetailViewModel,
    ) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumVm as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> detailVmFactory() as T
                else -> throw IllegalArgumentException("Unexpected VM $modelClass")
            }
        }
    }

    private class ProbeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val settingsFlow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = settingsFlow
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
        override suspend fun setForumServer(server: String) = settingsFlow.update { it.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = settingsFlow.update { it.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = settingsFlow.update { it.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = settingsFlow.update { it.copy(sidebarCollapsedPortrait = collapsed) }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = settingsFlow.update { it.copy(sidebarCollapsedLandscape = collapsed) }
    }

    private class ProbeAuthRepository(initial: SavedAccount? = null) : AuthRepository {
        private val _account = MutableStateFlow(initial)
        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initial))
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            if (initial != null) AuthState.SignedIn(initial) else AuthState.SignedOut,
        )
        override val sessionEpoch: Long = 1L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() { _account.value = null }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class ProbeFavoriteRepository(
        private val favoriteItems: List<FavoriteThreadItem> = emptyList(),
    ) : FavoriteRepository {
        override val favoriteTids = MutableStateFlow(favoriteItems.map { it.tid }.toSet())
        override val attentionTids = MutableStateFlow<Set<String>>(emptySet())
        override fun isFavorite(tid: String) = tid in favoriteTids.value
        override fun isAttention(tid: String) = tid in attentionTids.value
        override suspend fun getList(kind: FavoriteKind, page: Int, priority: AccessPriority): FavoriteListResult =
            FavoriteListResult.Success(
                kind = kind,
                page = page,
                listPage = FavoritesListPage(
                    maxPage = 1,
                    items = if (kind == FavoriteKind.FAVORITE) favoriteItems else emptyList(),
                    meta = probeMeta(),
                ),
            )
        override suspend fun syncCache(kind: FavoriteKind, maxPages: Int, priority: AccessPriority): FavoriteSyncResult =
            FavoriteSyncResult.Success(kind, favoriteTids.value, 1, 1, 0L)
        override suspend fun add(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult =
            FavoriteMutationResult.Success(kind, tid, "ok")
        override suspend fun remove(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult =
            FavoriteMutationResult.Success(kind, tid, "ok")
        override suspend fun removeByForm(kind: FavoriteKind, tid: String, formhash: String, priority: AccessPriority): FavoriteMutationResult =
            FavoriteMutationResult.Success(kind, tid, "ok")
    }

    private class ProbeThreadListRepository : ThreadListRepository {
        private val flows = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        private fun stateOf(q: ThreadListQuery) = flows.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }
        override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
            refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
        override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
            val summaries = (1..15).map { i ->
                val tid = (1000 + i).toString()
                ThreadSummary(tid, "探针论坛帖子 $tid", null, null, false, false, false, "作者$i", "$i", null, "2026-11-26", "刚刚", "回复$i", "10", "100", false, 2)
            }
            val items = summaries.map { ThreadListItem(it, false) }
            stateOf(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, probeMeta(), isLoading = false)
            return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 2, true, 1, probeMeta())
        }
        override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
            refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
        override fun observeThreadList(query: ThreadListQuery) = stateOf(query)
        override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
    }

    private class ProbeSearchRepository(
        private val searchResults: List<SearchItem> = emptyList(),
    ) : SearchRepository {
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
            SearchResult.Success(page, false, SearchPage("s1", 1, searchResults, probeMeta()))
        override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult =
            SearchResult.Success(1, false, SearchPage("s1", 1, emptyList(), probeMeta()))
        override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
        override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
        override suspend fun recordSearchHistory(query: String) = emptyList<String>()
        override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
        override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
        override suspend fun clearSearchHistory() = Unit
        override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
    }

    private data class SavedReadingCall(
        val tid: String,
        val page: Int,
        val pid: String,
        val offsetPx: Int,
        val filterMode: ReadingFilterMode,
    )

    private class ProbeThreadDetailRepository(
        private val lastPage: Int = 10,
        private val imagePages: Map<Pair<String, Int>, ContentBlock.Image> = emptyMap(),
    ) : ThreadDetailRepository {
        val preSavedPositions = LinkedHashMap<String, ReadingPositionEntity>()
        val pageDelays = LinkedHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()
        val saveDelaysByTid = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val loadPageCalls = CopyOnWriteArrayList<Pair<String, Int>>()
        val saveStartedCalls = CopyOnWriteArrayList<SavedReadingCall>()
        val saveCalls = CopyOnWriteArrayList<SavedReadingCall>()
        val getReadingPositionCalls = AtomicInteger(0)
        private val getPositionCountsByTid = ConcurrentHashMap<String, AtomicInteger>()

        fun getReadingPositionCount(tid: String): Int = getPositionCountsByTid[tid]?.get() ?: 0

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadPageCalls.add(tid to page)
            pageDelays[tid to page]?.await()
            val pageImage = imagePages[tid to page]
            val floors = (1..15).map { idx ->
                val floorNo = (page - 1) * 15 + idx
                val pid = "p${page * 100 + idx}"
                val blocks = buildList {
                    if (idx == 1 && pageImage != null) {
                        add(pageImage)
                    }
                    add(
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain("帖子 $tid 第 $page 页 第 $floorNo 楼 ($pid) 测试正文。"),
                            ),
                        ),
                    )
                }
                PostFloor(
                    postId = pid,
                    floor = floorNo,
                    page = page,
                    uid = if (floorNo == 1) "100" else "$idx",
                    author = if (floorNo == 1) "楼主" else "坛友$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 10:00",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = blocks,
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "探针帖子 $tid", page, lastPage, floors, lastPage * 15, probeMeta(), page < lastPage),
                filter = filter,
                threadAuthorId = "100",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, lastPage, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val resolvedTid = tid ?: "8001"
            val page = pid.removePrefix("p").toIntOrNull()?.div(100)?.coerceIn(1, lastPage) ?: 1
            val res = loadPage(resolvedTid, page, ThreadDetailFilter.All, recordHistory, forumName, priority) as ThreadDetailLoadResult.Success
            return res.copy(targetPostId = pid)
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? {
            getReadingPositionCalls.incrementAndGet()
            getPositionCountsByTid.computeIfAbsent(tid) { AtomicInteger(0) }.incrementAndGet()
            return preSavedPositions[tid]
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
            val call = SavedReadingCall(tid, page, pid, offsetPx, filterMode)
            saveStartedCalls.add(call)
            saveDelaysByTid[tid]?.await()
            saveCalls.add(call)
            preSavedPositions[tid] = ReadingPositionEntity(
                tid = tid,
                page = page,
                pid = pid,
                offsetPx = offsetPx,
                filterMode = filterMode,
                filterUid = filterUid,
                filterUserName = filterUserName,
                updatedAt = System.currentTimeMillis(),
            )
        }

        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>) =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }

        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>) = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) =
            floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }

    private fun waitUntilRealCondition(
        timeoutMs: Long,
        onTimeoutMessage: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(15L)
        }
        composeRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(onTimeoutMessage(), predicate())
    }

    private fun createOfflineImageLoader(): ImageLoader {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        SuccessResult(
                            image = ColorImage(AndroidColor.LTGRAY, width = 320, height = 240),
                            request = chain.request,
                            dataSource = DataSource.MEMORY_CACHE,
                        )
                    },
                )
            }
            .build()
    }

    private class ProbeSmsRepository : SmsRepository {
        override val cooldownUntilMs = MutableStateFlow(0L)
        override fun getRemainingCooldownSeconds() = 0
        override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority) =
            MessageResult.Success(
                SmsListPage(
                    items = listOf(
                        net.jejer.hipda.parser.message.SmsConversationItem(
                            uid = "100",
                            author = "消息探针用户",
                            avatarUrl = null,
                            time = "今天",
                            summary = "短消息摘要",
                            isNew = false,
                        ),
                    ),
                    meta = probeMeta(),
                    hasContainer = true,
                ),
            )
        override suspend fun getConversationDetail(uid: String, priority: AccessPriority) =
            MessageResult.Success(SmsDetailPage("42", "HiProbeUser", "abc", emptyList(), probeMeta(), true))
        override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority) = SmsSendOutcome.Sent
        override suspend fun clearConversation(uid: String, priority: AccessPriority) = SmsClearOutcome.Cleared
    }

    private class ProbeNotifyRepository(
        notifyEntries: List<NotifyEntry> = emptyList(),
    ) : NotifyRepository {
        override val smsUnreadCount = MutableStateFlow(0)
        override val notifyUnreadCount = MutableStateFlow(notifyEntries.count { !it.isRead })
        override val notifications = MutableStateFlow(notifyEntries)
        override suspend fun getNotifications(priority: AccessPriority) =
            MessageResult.Success(NotifyFeed(notifications.value, probeMeta(), true))
        override suspend fun markNotificationRead(key: String) {
            notifications.update { list -> list.map { if (it.key == key) it.copy(isRead = true) else it } }
        }
        override suspend fun markAllNotificationsRead() {
            notifications.update { list -> list.map { it.copy(isRead = true) } }
        }
        override suspend fun pollUnreadCounts(priority: AccessPriority) =
            NotificationPollResult.Success(UnreadSnapshot(0, 0))
        override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
        override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) = Unit
        override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) = Unit
    }

    private class ProbeBrowseHistoryDao : BrowseHistoryDao {
        private val items = MutableStateFlow<List<BrowseHistoryEntity>>(emptyList())
        override fun observeRecent(limit: Int) = items
        override fun observeVisitedTids(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun upsert(entity: BrowseHistoryEntity) = Unit
        override suspend fun delete(tid: String) = Unit
        override suspend fun restore(entity: BrowseHistoryEntity) = Unit
        override suspend fun trimTo(max: Int) = Unit
    }

    private class ProbeBlacklistRepository : BlacklistRepository {
        override val blockedUsernames = MutableStateFlow<Set<String>>(emptySet())
        override val lastSyncedAtMs = MutableStateFlow<Long?>(null)
        override fun isBlocked(username: String) = false
        override suspend fun syncBlacklist(priority: AccessPriority) =
            BlacklistSyncResult.Success(
                BlacklistSnapshot(ownerUid = "42", usernames = blockedUsernames.value, lastSyncedAtMs = null),
                isEmptyConfirmed = true,
                formhash = "abc",
            )
        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority) =
            BlacklistMutationResult.Success(username, blockedUsernames.value)
        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority) =
            BlacklistMutationResult.Success(username, blockedUsernames.value)
    }

    private class ProbeUserRepository : UserRepository {
        override suspend fun getUserProfile(uid: String, priority: AccessPriority) =
            UserProfileResult.Success(
                UserProfile(uid, "楼主", isOnline = true, avatarUrl = null, formhash = "abc", detail = "", meta = probeMeta()),
            )
        override suspend fun getUserThreads(uid: String, page: Int, searchId: String?, priority: AccessPriority) =
            UserThreadsResult.Success(uid, page, SearchPage("s1", 1, emptyList(), probeMeta()))
    }

    private class ProbeNotificationScheduler : NotificationScheduler {
        override fun syncSchedule(notificationsEnabled: Boolean) = Unit
        override suspend fun syncFromSettings() = Unit
        override fun schedule() = Unit
        override fun cancel() = Unit
    }

    private companion object {
        fun probeMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "42", username = "HiProbeUser"),
            formhash = "abc12345",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )
    }
}
