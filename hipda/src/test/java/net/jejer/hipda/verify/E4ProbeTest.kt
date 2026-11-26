package net.jejer.hipda.verify

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
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
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
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
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.glass.assertWcagContrastAtLeast
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.glass.resolveHiGlassStyleSpec
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
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
    // 离线测试替身与辅助函数
    // -------------------------------------------------------------------------

    private fun advanceVirtualAndMainLooper(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
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

    private class ProbeFavoriteRepository : FavoriteRepository {
        override val favoriteTids = MutableStateFlow<Set<String>>(emptySet())
        override val attentionTids = MutableStateFlow<Set<String>>(emptySet())
        override fun isFavorite(tid: String) = tid in favoriteTids.value
        override fun isAttention(tid: String) = tid in attentionTids.value
        override suspend fun getList(kind: FavoriteKind, page: Int, priority: AccessPriority): FavoriteListResult =
            FavoriteError.SiteError("offline")
        override suspend fun syncCache(kind: FavoriteKind, maxPages: Int, priority: AccessPriority): FavoriteSyncResult =
            FavoriteError.SiteError("offline")
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

    private class ProbeSearchRepository : SearchRepository {
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
            getNewPosts(page, searchId, priority)
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
    ) : ThreadDetailRepository {
        val preSavedPositions = LinkedHashMap<String, ReadingPositionEntity>()
        val pageDelays = LinkedHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()
        val loadPageCalls = CopyOnWriteArrayList<Pair<String, Int>>()
        val saveCalls = CopyOnWriteArrayList<SavedReadingCall>()
        val getReadingPositionCalls = AtomicInteger(0)

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
            val floors = (1..15).map { idx ->
                val floorNo = (page - 1) * 15 + idx
                val pid = "p${page * 100 + idx}"
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
                    blocks = listOf(
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain("帖子 $tid 第 $page 页 第 $floorNo 楼 ($pid) 测试正文。"),
                            ),
                        ),
                    ),
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
            val page = 1
            val res = loadPage(resolvedTid, page, ThreadDetailFilter.All, recordHistory, forumName, priority) as ThreadDetailLoadResult.Success
            return res.copy(targetPostId = pid)
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? {
            getReadingPositionCalls.incrementAndGet()
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
            saveCalls.add(SavedReadingCall(tid, page, pid, offsetPx, filterMode))
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

    private companion object {
        fun probeMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "42", username = "HiProbeUser"),
            formhash = "abc12345",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )
    }
}
