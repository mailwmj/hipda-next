package net.jejer.hipda.verify

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.up
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowInfoTrackerDecorator
import androidx.window.layout.WindowLayoutInfo
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.favorite.FavoriteMutationResult
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteSyncResult
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.local.observe
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
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
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
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.ComposeSlotsEntryPoint
import net.jejer.hipda.feature.compose.FullEditorHandoff
import net.jejer.hipda.feature.compose.FullEditorLauncher
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelHost
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelRenderer
import net.jejer.hipda.feature.compose.QuickReplyEvent
import net.jejer.hipda.feature.compose.QuickReplyViewModel
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
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.parser.user.UserProfile
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * V-E5 独立验证探针（基于 E5 任务卡、产品设计规范 §4.2/§5/§6.3/§7.2/§7.3 与生产接口编写；
 * 100% 离线，仅使用 MockWebServer 与内存替身，绝不访问 4d4y.com，绝不修改生产代码或既有测试）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class E5ProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun initOkHttp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    // =========================================================================
    // 探针 1：合入 main 真实链路 IME 逐帧升降、高度钳制、矮屏紧凑态与减动效 0ms
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe1a_nextAppRoot_imeFrameByFrameRiseAndFall_clampsHeightAndTracksImeZeroLag() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), ProbeSearchRepository(), settingsRepo, authRepo, gateway)
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
            quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
        )

        var hostView: android.view.View? = null
        try {
            composeRule.setContent {
                hostView = LocalView.current
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = settingsRepo.settings,
                            currentAccountFlow = authRepo.currentAccount,
                            smsUnreadCountFlow = MutableStateFlow(0),
                            notifyUnreadCountFlow = MutableStateFlow(0),
                            imageLoader = createOfflineImageLoader(),
                            hostViewModel = hostVm,
                            forumViewModel = forumVm,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            // 从论坛打开帖子 1001 并点击底部三段工具条「回复」
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()

            // 输入足够多行正文，迫使面板高度触及上限以检验 min(300dp, 可视净高 × 60%) 钳制
            val multiLineBody = (1..16).joinToString("\n") { "逐帧高度测试第 $it 行内容" }
            composeRule.onNode(hasSetTextAction()).performTextReplacement(multiLineBody)
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val paneHeightDp = with(density) { rootBounds.height.toDp().value }

            fun sheetNode() = composeRule.onNode(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                useUnmergedTree = true,
            )

            // 关闭 autoAdvance，模拟 IME Insets 逐帧升起再回落，校验每帧零脱节与高度钳制
            composeRule.mainClock.autoAdvance = false
            try {
                val imeStepDps = listOf(40, 120, 220, 320, 440, 560, 620, 440, 220, 0)
                for (imeDp in imeStepDps) {
                    val imePx = with(density) { imeDp.dp.roundToPx() }
                    val insets = WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                        .build()
                    composeRule.runOnUiThread {
                        ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
                    }
                    Snapshot.sendApplyNotifications()
                    composeRule.mainClock.advanceTimeByFrame()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    composeRule.mainClock.advanceTimeByFrame()

                    val bounds = sheetNode().fetchSemanticsNode().boundsInRoot
                    val expectedBottomPx = rootBounds.bottom - imePx
                    assertTrue(
                        "IME=${imeDp}dp 时面板底边 ${bounds.bottom} 必须贴合键盘上方 $expectedBottomPx",
                        abs(bounds.bottom - expectedBottomPx) <= 2f,
                    )

                    val visibleNetDp = (paneHeightDp - imeDp).coerceAtLeast(0f)
                    val expectedMaxHeightDp = min(300f, visibleNetDp * 0.60f)
                    val actualHeightDp = with(density) { bounds.height.toDp().value }
                    assertTrue(
                        "IME=${imeDp}dp (净高=${visibleNetDp}dp) 面板高 ${actualHeightDp}dp 超过上限 min(300, 60%)=$expectedMaxHeightDp",
                        actualHeightDp <= expectedMaxHeightDp + 0.5f,
                    )

                    // 净高 >= 180dp 时，把手与发送按钮必须完整可见且位于面板内部
                    if (visibleNetDp >= 180f) {
                        val sendBounds = composeRule.onNodeWithText("发送").fetchSemanticsNode().boundsInRoot
                        assertTrue("发送按钮不能溢出面板下沿", sendBounds.bottom <= bounds.bottom + 1f)
                        assertTrue("发送按钮不能溢出面板上沿", sendBounds.top >= bounds.top - 1f)
                    }
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        } finally {
            db.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun probe1b_nextAppRoot_shortScreenUnder180dp_hidesHandleAndDraftBannerZeroClipping() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        runBlocking {
            db.draftDao().upsert(
                DraftEntity(
                    key = DraftKey.reply("1001").value,
                    content = "矮屏已保存旧草稿内容",
                    updatedAt = fakeClock.get(),
                ),
            )
        }
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), ProbeSearchRepository(), settingsRepo, authRepo, gateway)
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
            quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
        )

        var hostView: android.view.View? = null
        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        val fakeCtx = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = null,
        )
        try {
            composeRule.setContent {
                hostView = LocalView.current
                val activityCtx = LocalContext.current
                val wrappedCtx = remember(activityCtx) {
                    object : ContextWrapper(activityCtx) {
                        override fun getApplicationContext(): Context = fakeCtx.applicationContext
                    }
                }
                CompositionLocalProvider(LocalContext provides wrappedCtx) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                            NextAppRoot(
                                settingsFlow = settingsRepo.settings,
                                currentAccountFlow = authRepo.currentAccount,
                                smsUnreadCountFlow = MutableStateFlow(0),
                                notifyUnreadCountFlow = MutableStateFlow(0),
                                imageLoader = createOfflineImageLoader(),
                                hostViewModel = hostVm,
                                forumViewModel = forumVm,
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            waitUntilRealCondition(3_000L, { "Room 草稿「已恢复上次草稿」未加载显示" }) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }

            // IME 未弹出（净高 411dp >= 180dp）：把手与「已恢复上次草稿」横条正常显示
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()

            // 模拟横屏弹出 245dp 键盘：可视净高 = 411 - 245 = 166dp < 180dp -> 隐藏把手与草稿横条，输入与发送零裁切
            val ime245Px = with(composeRule.density) { 245.dp.roundToPx() }
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    requireNotNull(hostView),
                    WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime245Px)).build(),
                )
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
            composeRule.onNodeWithText("已恢复上次草稿").assertDoesNotExist()
            composeRule.onNodeWithText("清空").assertDoesNotExist()

            val sheetBounds = composeRule.onNode(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot
            val maxAllowedPx = with(composeRule.density) { (166f * 0.60f).dp.toPx() }
            assertTrue("166dp 净高下面板高 ${sheetBounds.height}px 不得超限 $maxAllowedPx", sheetBounds.height <= maxAllowedPx + 2f)

            val inputBounds = composeRule.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val sendBtn = composeRule.onNodeWithText("发送").assertIsDisplayed()
            sendBtn.assertHeightIsAtLeast(48.dp)
            val sendBounds = sendBtn.fetchSemanticsNode().boundsInRoot
            val expandBtn = composeRule.onNodeWithContentDescription("展开全屏").assertIsDisplayed()
            expandBtn.assertHeightIsAtLeast(48.dp)
            val expandBounds = expandBtn.fetchSemanticsNode().boundsInRoot

            assertTrue("输入框不得裁切溢出面板上沿", inputBounds.top >= sheetBounds.top - 1f)
            assertTrue("输入框不得裁切溢出面板下沿", inputBounds.bottom <= sheetBounds.bottom + 1f)
            assertTrue("发送按钮不得裁切溢出面板上沿", sendBounds.top >= sheetBounds.top - 1f)
            assertTrue("发送按钮不得裁切溢出面板下沿", sendBounds.bottom <= sheetBounds.bottom + 1f)
            assertTrue("展开全屏按钮不得裁切溢出面板", expandBounds.top >= sheetBounds.top - 1f && expandBounds.bottom <= sheetBounds.bottom + 1f)

            // 极端矮屏净高 < 120dp（ime=295dp -> 116dp 净高，上限 69.6dp）：仍可完整操作「展开全屏」且零裁切
            val ime295Px = with(composeRule.density) { 295.dp.roundToPx() }
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    requireNotNull(hostView),
                    WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime295Px)).build(),
                )
            }
            composeRule.waitForIdle()
            val extremeSheetBounds = composeRule.onNode(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot
            val extremeSendBounds = composeRule.onNodeWithText("发送").fetchSemanticsNode().boundsInRoot
            assertTrue("净高 <120dp 时发送按钮仍不得裁切", extremeSendBounds.bottom <= extremeSheetBounds.bottom + 1f)
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            assertEquals(1, capturedHandoffs.size)
            assertEquals("矮屏已保存旧草稿内容", capturedHandoffs.single().text)
        } finally {
            db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun probe1c_reducedMotion_zeroScale_opensDismissesAndExpandsInSingleFrame() = withAnimatorDurationScale(0f) {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val vm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)
        val handoffs = mutableListOf<FullEditorHandoff>()

        try {
            setContent {
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", title = "减动效探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = vm,
                                    quickReplyViewModel = qrVm,
                                    onExpandToFullEditor = { handoffs.add(it) },
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
            }
            waitForIdle()

            mainClock.autoAdvance = false
            try {
                // 1. 减动效打开：1 帧直接到达终态，次帧无中间位移
                onNodeWithText("回复").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                mainClock.advanceTimeByFrame()

                val sendNode = onNodeWithText("发送").assertIsDisplayed()
                val firstY = sendNode.fetchSemanticsNode().boundsInRoot.top
                mainClock.advanceTimeByFrame()
                val secondY = onNodeWithText("发送").fetchSemanticsNode().boundsInRoot.top
                assertEquals("减动效打开应 1 帧到位，次帧不得继续移动", firstY, secondY, 0.5f)

                // 2. 减动效关闭：1 帧直接卸载并恢复底部工具条
                onNodeWithContentDescription("收起回复面板").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
                onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

                // 3. 减动效展开全屏：1 帧直接交接并关闭面板
                onNodeWithText("回复").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onNodeWithContentDescription("展开全屏").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                assertEquals(1, handoffs.size)
                onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
            } finally {
                mainClock.autoAdvance = true
            }
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 探针 2：三栏（749dp / 933dp）与铰链双栏右栏边界 & 玻璃预算
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe2a_threePane749dp_quickReplyStrictlyInRightPaneAndBottomGlassUnmounted() {
        verifyThreePaneQuickReplyBoundsAndGlassBudget()
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun probe2b_threePane933dp_quickReplyStrictlyInRightPaneAndBottomGlassUnmounted() {
        verifyThreePaneQuickReplyBoundsAndGlassBudget()
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe2c_hingeTwoPane749dp_quickReplyRightOfHingeAndGlassBudget() {
        val separatingHinge = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(separatingHinge)) {
            val db = createInMemoryDb()
            val fakeClock = AtomicLong(1_000_000L)
            val detailRepo = ProbeThreadDetailRepository()
            val authRepo = ProbeAuthRepository(signedInAccount())
            val settingsRepo = ProbeSettingsRepository()
            val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
            val gateway = createOfflineGateway()
            val hostVm = NextAppHostViewModel(settingsRepo)
            val forumVm = ForumViewModel(ProbeThreadListRepository(), ProbeSearchRepository(), settingsRepo, authRepo, gateway)
            val owner = ProbeViewModelStoreOwner(
                hostVm = hostVm,
                forumVm = forumVm,
                detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
                quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
            )
            try {
                composeRule.setContent {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                            NextAppRoot(
                                settingsFlow = settingsRepo.settings,
                                currentAccountFlow = authRepo.currentAccount,
                                smsUnreadCountFlow = MutableStateFlow(0),
                                notifyUnreadCountFlow = MutableStateFlow(0),
                                imageLoader = createOfflineImageLoader(),
                                hostViewModel = hostVm,
                                forumViewModel = forumVm,
                            )
                        }
                    }
                }
                composeRule.waitForIdle()

                composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
                advanceVirtualAndMainLooper(250L)

                assertQuickReplyRightPaneBoundsAndGlassBudget(expectedMinLeftDp = 378f)
            } finally {
                db.close()
            }
        }
    }

    private fun verifyThreePaneQuickReplyBoundsAndGlassBudget() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), ProbeSearchRepository(), settingsRepo, authRepo, gateway)
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
            quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
        )
        try {
            composeRule.setContent {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = settingsRepo.settings,
                            currentAccountFlow = authRepo.currentAccount,
                            smsUnreadCountFlow = MutableStateFlow(0),
                            notifyUnreadCountFlow = MutableStateFlow(0),
                            imageLoader = createOfflineImageLoader(),
                            hostViewModel = hostVm,
                            forumViewModel = forumVm,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)

            assertQuickReplyRightPaneBoundsAndGlassBudget(expectedMinLeftDp = null)
        } finally {
            db.close()
        }
    }

    private fun assertQuickReplyRightPaneBoundsAndGlassBudget(expectedMinLeftDp: Float?) {
        // 打开前基线：3 个玻璃区域、<=2 采样源、<=5 drawBackdrop
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        assertEquals(3, countGlassMarkers())
        val beforeLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        assertTrue(countModifierName(beforeLayout, "LayerBackdrop") <= 2)
        assertEquals(0, countNestedLayerBackdrops(beforeLayout))
        assertTrue(countModifierName(beforeLayout, "DrawBackdrop") <= 5)
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 展开快速回复面板
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        val detailPaneBounds = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文"),
        ).fetchSemanticsNode().boundsInRoot
        if (expectedMinLeftDp != null) {
            val expectedMinLeftPx = with(composeRule.density) { expectedMinLeftDp.dp.toPx() }
            assertTrue("右栏必须严格位于铰链右侧", detailPaneBounds.left >= expectedMinLeftPx - 1f)
        }

        val sheetBounds = composeRule.onNode(
            SemanticsMatcher.expectValue(HiGlassMarker, true) and
                hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("快速回复面板左缘 ${sheetBounds.left} 不得跨出右栏左界 ${detailPaneBounds.left}", sheetBounds.left >= detailPaneBounds.left - 0.5f)
        assertTrue("快速回复面板右缘 ${sheetBounds.right} 不得跨出右栏右界 ${detailPaneBounds.right}", sheetBounds.right <= detailPaneBounds.right + 0.5f)

        // 查找暗罩（与右栏等宽高的可点击节点，且非回复面板内部子节点）
        val scrimNode = composeRule.onAllNodes(
            hasClickAction() and
                hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文")) and
                !hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().firstOrNull {
            it.boundsInRoot.height >= detailPaneBounds.height * 0.8f && it.boundsInRoot.width >= detailPaneBounds.width * 0.8f
        }
        assertNotNull("右栏内必须存在覆盖右栏的暗罩节点", scrimNode)
        val scrimBounds = scrimNode!!.boundsInRoot
        assertTrue("暗罩左缘不得跨分隔线/铰链遮挡左中栏", scrimBounds.left >= detailPaneBounds.left - 0.5f)
        assertTrue("暗罩右缘不得越界", scrimBounds.right <= detailPaneBounds.right + 0.5f)

        // 底部工具条必须彻底卸载（HiGlassMarker 与 DrawBackdropElement 释放），总玻璃预算仍 ≤ 3 / ≤ 2 / ≤ 5
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("收藏").assertDoesNotExist()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        assertEquals("展开快速回复时全屏 HiGlassMarker 必须保持为 3（卸载了底部工具条）", 3, countGlassMarkers())
        val openLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        assertTrue("LayerBackdrop 必须 <= 2", countModifierName(openLayout, "LayerBackdrop") <= 2)
        assertEquals("禁止嵌套 LayerBackdrop", 0, countNestedLayerBackdrops(openLayout))
        assertEquals("展开快速回复时全屏静止 DrawBackdrop 应为 3（<= 5）", 3, countModifierName(openLayout, "DrawBackdrop"))

        // 左栏/中栏未被暗罩阻塞：中栏帖子仍可见且位于右栏左侧
        val midThreadBounds = composeRule.onNodeWithText("探针论坛帖子 1002").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(midThreadBounds.right <= detailPaneBounds.left + 1f)

        // 点暗罩关闭面板后，底部三段工具条及其玻璃完整恢复
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").assertIsDisplayed()
        assertEquals(3, countGlassMarkers())
        val closedLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        assertTrue(countModifierName(closedLayout, "LayerBackdrop") <= 2)
        assertTrue(countModifierName(closedLayout, "DrawBackdrop") <= 5)
    }

    // =========================================================================
    // 探针 3：合入 main 返回优先级（看图/抽屉/快速回复/PositionStack/三栏）与手势隔离 + R-D6 S2
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe3a_mergedBackPriority_imageViewer_drawer_quickReply_positionStack_threePane_noPenetration() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val imageBlock = ContentBlock.Image(
            fullUrl = "https://mock.local/forum/attachments/probe_1001.jpg",
            thumbUrl = "https://mock.local/forum/attachments/probe_1001_thumb.jpg",
            fileSizeBytes = 12_345L,
        )
        val detailRepo = ProbeThreadDetailRepository(imagePages = mapOf(("1001" to 1) to imageBlock))
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val imageLoader = createOfflineImageLoader()
        val imageViewerRenderer = ImageViewerRendererImpl(
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
            quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
        )

        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        try {
            composeRule.setContent {
                backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = settingsRepo.settings,
                            currentAccountFlow = authRepo.currentAccount,
                            smsUnreadCountFlow = MutableStateFlow(0),
                            notifyUnreadCountFlow = MutableStateFlow(0),
                            imageLoader = imageLoader,
                            loginOverlayRenderer = LoginOverlayRendererImpl(authRepo, gateway),
                            messagesPaneRenderer = MessagesPaneRendererImpl(ProbeSmsRepository(), ProbeNotifyRepository(), authRepo, gateway),
                            searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                            settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                                ApplicationProvider.getApplicationContext(),
                                settingsRepo,
                                ProbeNotificationScheduler(),
                                imageLoader,
                                authRepo,
                                ProbeBlacklistRepository(),
                                ProbeUserRepository(),
                            ),
                            imageViewerRenderer = imageViewerRenderer,
                            hostViewModel = hostVm,
                            forumViewModel = forumVm,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            // 1. 打开帖子 1001，跳至第 4 页再跳回「首页」，压入 PositionStack
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("末页").performClick()
            advanceVirtualAndMainLooper(150L)
            composeRule.onNodeWithContentDescription("第 10 页，共 10 页，点按展开跳页").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("首页").performClick()
            advanceVirtualAndMainLooper(150L)

            val detailVm = ViewModelProvider(
                hostVm.viewModelStoreOwnerForThread("1001", owner.defaultViewModelProviderFactory),
            )["thread:1001", ThreadDetailViewModel::class.java]
            assertEquals(1, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)

            // 2. 打开快速回复面板并输入草稿
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("五层栈草稿内容")
            composeRule.waitForIdle()

            // 3a. 在快速回复之上打开全屏看图（E14 Overlay 挂载于 NextAppRoot 顶层），验证看图 > 快速回复 > PositionStack
            waitUntilRealCondition(5_000L, { "楼层图片未加载" }) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceUntilVirtualCondition(3_000L) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isNotEmpty()
            }

            // 第 1 层返回（含 Dismissing 退场期连按）：只关闭「看图」，绝不穿透快速回复/位置栈
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() } // 退场期二次连按必须被吞掉
                composeRule.mainClock.advanceTimeByFrame()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(3_000L) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            composeRule.onNodeWithText("五层栈草稿内容").assertIsDisplayed()
            assertEquals(1, detailVm.uiState.value.page)

            // 3b. 再在快速回复之上打开 240dp 模态抽屉（SidebarToggleButton 仍在左侧 64dp 轨上）
            composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

            // 第 2 层返回：只关闭模态抽屉，快速回复与输入文字完整保留
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            composeRule.onNodeWithText("五层栈草稿内容").assertIsDisplayed()
            assertEquals(1, detailVm.uiState.value.page)

            // 第 3 层返回（含退场期连按）：只收起快速回复并静默保存草稿，绝不穿透弹出 PositionStack
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() } // Dismissing 期间连按必须被吞掉
                composeRule.mainClock.advanceTimeByFrame()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            assertEquals("关闭快速回复不得穿透弹出 PositionStack", 1, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)
            composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
            val savedDraft = runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }
            assertEquals("五层栈草稿内容", savedDraft?.content)

            // 第 4a 层返回：第一次弹出 PositionStack 回到第 10 页（栈中仍有第 1 页，帖子仍在右栏）
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            advanceVirtualAndMainLooper(200L)
            assertEquals(10, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)
            assertNotNull(hostVm.state.value.selectedThread)

            // 第 4b 层返回：第二次弹出 PositionStack 回到第 1 页（位置栈清空，帖子仍在右栏）
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            advanceVirtualAndMainLooper(200L)
            assertEquals(1, detailVm.uiState.value.page)
            assertFalse(detailVm.uiState.value.canPopPositionStack)
            assertNotNull(hostVm.state.value.selectedThread)

            // 第 5 层返回：位置栈已空，交由 E3 多栏返回栈关闭右栏回到占位
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            composeRule.waitForIdle()
            assertNull(hostVm.state.value.selectedThread)
            composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun probe3b_singleBatchHandleDrag_dismissesQuickReplySheet() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        try {
            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", title = "单批拖拽阻断探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("单批快速下拉草稿")
            composeRule.waitForIdle()

            // 在同一帧/单次 moveBy 下完成 >= 72dp 把手下拉并抬手（真实快速轻扫或合批触摸常见路径）
            composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                down(center)
                moveBy(Offset(0f, with(composeRule.density) { 120.dp.toPx() }), delayMillis = 40L)
                up()
            }
            composeRule.waitForIdle()
            assertEquals(
                "把手越阈值单批下拉(down->moveBy 120dp->up)未能收起面板：QuickReplySheet.onDragDelta 使用异步 scope.launch{snapTo} 且 onDragSettled 同步读 offsetYPx.value 导致偏移为 0 弹回",
                0,
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().size,
            )
            composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
            assertEquals("单批快速下拉草稿", runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }?.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun probe3d_singleBatchTopOverscroll_dismissesQuickReplySheet() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        try {
            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", title = "单批顶边超界拖拽探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("单批顶边超界下拉草稿")
            composeRule.waitForIdle()

            composeRule.onNode(hasSetTextAction()).performTouchInput {
                down(Offset(width * 0.5f, height * 0.2f))
                moveBy(Offset(0f, with(composeRule.density) { 160.dp.toPx() }), delayMillis = 40L)
                up()
            }
            composeRule.waitForIdle()
            assertEquals(
                "顶边无选区超界单批下拉(down->moveBy 160dp->up)未能收起面板：inputNestedScroll.onPreFling 读到的 offsetYPx.value 仍为 0",
                0,
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().size,
            )
            composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun probe3c_gestureIsolation_steppedHandleAndOverscroll_undoDiscard_andRd6S2() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        fun steppedVerticalDrag(
            node: androidx.compose.ui.test.SemanticsNodeInteraction,
            startFractionY: Float,
            totalDeltaDp: Dp,
            steps: Int = 8,
            stepDelayMs: Long = 16L,
        ) {
            val totalPx = with(composeRule.density) { totalDeltaDp.toPx() }
            node.performTouchInput {
                down(Offset(width * 0.5f, height * startFractionY))
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val stepPx = totalPx / steps
            repeat(steps) {
                node.performTouchInput {
                    moveBy(Offset(0f, stepPx), delayMillis = stepDelayMs)
                }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            node.performTouchInput { up() }
            composeRule.waitForIdle()
        }

        var snackbarHostRef: SnackbarHostState? = null
        try {
            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }.also { snackbarHostRef = it }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", title = "手势与草稿探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // 1. 打开面板，填入超长多行文本触发内部滚动
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            val longLines = (1..20).joinToString("\n") { "第 $it 行用于测试多行输入纵向滚动隔离" }
            val inputNode = composeRule.onNode(hasSetTextAction())
            inputNode.performTextReplacement(longLines)
            composeRule.waitForIdle()

            // 1a. 在输入框内部向上滑动（浏览下方行）及未到顶部的向下回滚：绝不误关面板
            inputNode.performTouchInput {
                val cx = width * 0.5f
                down(Offset(cx, height * 0.80f))
                moveBy(Offset(0f, -height * 0.55f), delayMillis = 20L)
                up()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

            inputNode.performTouchInput {
                val cx = width * 0.5f
                down(Offset(cx, height * 0.30f))
                moveBy(Offset(0f, height * 0.25f), delayMillis = 20L)
                up()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

            // 1b. 有文本选区时，在顶边下拉也不应触发收起
            inputNode.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 6, true) }
            composeRule.waitForIdle()
            steppedVerticalDrag(inputNode, startFractionY = 0.2f, totalDeltaDp = 140.dp)
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            inputNode.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 0, true) }
            composeRule.waitForIdle()

            // 2. 把手小幅下拉（30dp < 72dp）弹回不关闭、不存草稿
            val handleNode = composeRule.onNodeWithContentDescription("收起回复面板")
            steppedVerticalDrag(handleNode, startFractionY = 0.5f, totalDeltaDp = 30.dp, steps = 4)
            handleNode.assertIsDisplayed()
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()

            // 3. 逐帧步进的把手越阈值下拉（120dp >= 72dp）关闭面板并保存草稿 + 弹「已保存草稿 | 放弃」，点「放弃」撤销草稿
            steppedVerticalDrag(handleNode, startFractionY = 0.5f, totalDeltaDp = 120.dp, steps = 8)
            composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
            composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
            assertEquals(longLines, runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }?.content)

            composeRule.onNodeWithText("放弃").performClick()
            waitUntilRealCondition(2_000L, { "点击「放弃」Snackbar 操作后草稿未删除" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() } == null
            }

            // 4. 多行滚回顶边且无选区时，输入框顶边超界下拉收起面板并存草稿
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("已恢复上次草稿").assertDoesNotExist()
            composeRule.onNode(hasSetTextAction()).performTextReplacement(longLines)
            composeRule.waitForIdle()
            // 先下滚再滚回最顶部，确保 scrollState.value 回到 0
            steppedVerticalDrag(composeRule.onNode(hasSetTextAction()), startFractionY = 0.8f, totalDeltaDp = (-120).dp, steps = 6)
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            steppedVerticalDrag(composeRule.onNode(hasSetTextAction()), startFractionY = 0.2f, totalDeltaDp = 160.dp, steps = 6)
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            // 确保清选区且位于顶边后再次顶边超界下拉
            composeRule.onNode(hasSetTextAction()).performTextReplacement("顶边超界下拉新草稿")
            composeRule.waitForIdle()
            steppedVerticalDrag(composeRule.onNode(hasSetTextAction()), startFractionY = 0.2f, totalDeltaDp = 160.dp, steps = 8)
            composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
            waitUntilRealCondition(2_000L, { "顶边超界下拉后未写入草稿" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }?.content == "顶边超界下拉新草稿"
            }

            // 先清理第 4 步产生的「已保存草稿」提示，以便精确断言第 5 步删除旧草稿退出不会再弹提示
            composeRule.runOnIdle { snackbarHostRef?.currentSnackbarData?.dismiss() }
            composeRule.waitForIdle()

            // 5. R-D6 S2 验证（路径 A：恢复旧草稿后手动删空文字退出 -> 必须 deleteDraft，重开不得复活）
            composeRule.onNodeWithText("回复").performClick()
            waitUntilRealCondition(2_000L, { "重开后未异步恢复「已恢复上次草稿」横条" }) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("   ")
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            waitUntilRealCondition(2_000L, { "R-D6 S2: 恢复旧草稿后手动删空退出必须 deleteDraft" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() } == null
            }

            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(200L)
            composeRule.onNodeWithText("已恢复上次草稿").assertDoesNotExist()
            composeRule.onNodeWithText("顶边超界下拉新草稿").assertDoesNotExist()

            // 6. R-D6 S2 验证（路径 B：恢复引用草稿后点「清空」按钮 -> deleteDraft 且不复活）
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p102", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("待清空的引用草稿")
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            waitUntilRealCondition(2_000L, { "引用草稿 quotePid 未落盘" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }?.quotePid == "p102"
            }

            composeRule.onNodeWithText("回复").performClick()
            waitUntilRealCondition(2_000L, { "引用草稿重开后未显示「已恢复上次草稿」" }) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
            composeRule.onNodeWithText("清空").performClick()
            waitUntilRealCondition(2_000L, { "点击清空后旧草稿必须立刻删除" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() } == null
            }
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(200L)
            composeRule.onNodeWithText("已恢复上次草稿").assertDoesNotExist()
            composeRule.onNodeWithText("待清空的引用草稿").assertDoesNotExist()
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 补充 (5)：绑定三个可选插槽假实现后，展开全屏/插入图片 FullEditorHandoff 无损且不弹「已保存草稿」
    // =========================================================================

    @Test
    fun probe5_optionalSlotsBinding_handoffTextSelectionQuotePreserved_andNoDraftSavedSnackbar() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        var slotsBound by mutableStateOf(false)
        val fakeEntryContext = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = object : QuickReplyEmojiPanelRenderer {
                @Composable
                override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
                    Column(modifier.fillMaxWidth().padding(8.dp)) {
                        Text("假表情面板高度=${host.keyboardHeightDp.value.toInt()}")
                        Button(onClick = { host.onInsertEmoji("[微笑]") }) { Text("插入假表情") }
                        Button(onClick = host.onBackspace) { Text("假表情退格") }
                    }
                }
            },
        )

        try {
            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                val baseCtx = LocalContext.current
                val boundCtx = remember(baseCtx) {
                    object : ContextWrapper(baseCtx) {
                        override fun getApplicationContext(): Context = fakeEntryContext.applicationContext
                    }
                }
                val providedCtx = if (slotsBound) boundCtx else baseCtx
                CompositionLocalProvider(
                    LocalContext provides providedCtx,
                    LocalPaneSnackbarController provides controller,
                ) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", fid = 2, title = "可选插槽探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // A. 未绑定插槽：三个按钮均不渲染，也不得有占位提示；把手上拉不触发全屏展开
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("展开全屏").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("插入图片").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("表情").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                down(center)
                moveBy(Offset(0f, -with(composeRule.density) { 100.dp.toPx() }), delayMillis = 30L)
                up()
            }
            composeRule.waitForIdle()
            assertEquals(0, capturedHandoffs.size)
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            composeRule.waitForIdle()

            // B. 绑定三个可选插槽的假实现（经 ComposeSlotsEntryPoint `@BindsOptionalOf` 路径解析）
            composeRule.runOnIdle { slotsBound = true }
            composeRule.waitForIdle()

            // 通过长按 2 楼点「引用回复」进入面板
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p102", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("展开全屏").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertHeightIsAtLeast(48.dp)

            // 验证「表情」插槽插入文本并切「键盘」
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("插入假表情").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // 填入完整测试文本并设置选区 [2, 6)，点击「展开全屏」
            val fullText = "引用正文XYZ测试"
            val inputNode = composeRule.onNode(hasSetTextAction())
            inputNode.performTextReplacement(fullText)
            inputNode.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 6, true) }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()

            assertEquals(1, capturedHandoffs.size)
            val expandHandoff = capturedHandoffs.last()
            assertEquals("1001", expandHandoff.tid)
            assertEquals(2, expandHandoff.fid)
            assertEquals(fullText, expandHandoff.text)
            assertEquals(2, expandHandoff.selectionStart)
            assertEquals(6, expandHandoff.selectionEnd)
            assertEquals("p102", expandHandoff.quotePid)
            assertEquals("坛友2", expandHandoff.quoteAuthor)
            assertEquals(2, expandHandoff.quoteFloor)
            assertFalse(expandHandoff.openImagePickerImmediately)
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()

            // C. 再次长按 3 楼点「引用回复」，设置文本与光标位置 [4, 4)，点「插入图片」
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p103", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()

            val imgText = "准备插图第1段"
            val imgInput = composeRule.onNode(hasSetTextAction())
            imgInput.performTextReplacement(imgText)
            imgInput.performSemanticsAction(SemanticsActions.SetSelection) { it(4, 4, true) }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("插入图片").performClick()
            composeRule.waitForIdle()

            assertEquals(2, capturedHandoffs.size)
            val imageHandoff = capturedHandoffs.last()
            assertEquals("1001", imageHandoff.tid)
            assertEquals(2, imageHandoff.fid)
            assertEquals(imgText, imageHandoff.text)
            assertEquals(4, imageHandoff.selectionStart)
            assertEquals(4, imageHandoff.selectionEnd)
            assertEquals("p103", imageHandoff.quotePid)
            assertEquals("坛友3", imageHandoff.quoteAuthor)
            assertEquals(3, imageHandoff.quoteFloor)
            assertTrue(imageHandoff.openImagePickerImmediately)
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("收起回复面板").assertDoesNotExist()
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 补充 (6)：30 秒冷却期间连点发送 0 次额外请求（MockWebServer 计数）+ 过滤模式/失败保留重试
    // =========================================================================

    @Test
    fun probe6_mockWebServer_30sCooldownSpamClicksSendZeroRequests_andFilterModeRetryWorks() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(2_000_000L)
        val server = MockWebServer()
        val recordedTargets = CopyOnWriteArrayList<String>()
        val postAttempts = AtomicInteger(0)
        val postGate = AtomicInteger(0) // 1 = block until latch released
        var activeLatch: CountDownLatch? = null
        val failNextPost = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                recordedTargets.add("${request.method} $target")
                return when {
                    request.method == "GET" && target.contains("post.php?action=reply") -> {
                        gbkHtmlResponse(prePostReplyHtml(formhash = "fh_probe_1", quotePid = if (target.contains("repquote=")) "101" else null))
                    }
                    request.method == "POST" && target.contains("post.php?action=reply") -> {
                        postAttempts.incrementAndGet()
                        if (postGate.get() == 1) {
                            activeLatch?.await(5, TimeUnit.SECONDS)
                        }
                        if (failNextPost.compareAndSet(1, 0)) {
                            gbkHtmlResponse(
                                """<html><body><div id="umenu"><cite><a href="space.php?uid=42" class="noborder">HiProbeUser</a></cite></div><div class="alert_error">内容包含禁止词</div></body></html>""",
                            )
                        } else {
                            MockResponse.Builder()
                                .code(302)
                                .addHeader("Location", "viewthread.php?tid=1001&page=10#pid999")
                                .build()
                        }
                    }
                    request.method == "GET" && target.contains("viewthread.php?tid=1001") -> {
                        gbkHtmlResponse(redirectedThreadLastPageHtml(tid = "1001", newPid = "p999"))
                    }
                    else -> MockResponse.Builder().code(404).body("Not found").build()
                }
            }
        }
        server.start()

        val serverUrl = server.url("").toString().trimEnd('/')
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val okHttp = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 Chrome/131.0.0.0 Safari/537.36" },
            transportInterceptor = TransportInterceptor({ null }, { false }),
        )
        val settingsRepo = ProbeSettingsRepository(AppSettings(forumServer = serverUrl))
        val authRepo = ProbeAuthRepository(signedInAccount())
        val gateway = SiteAccessGateway(okHttp, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val httpClient = ForumHttpClient(gateway, settingsRepo, authRepo)
        val postRepo = DefaultPostRepository(
            forumHttpClient = httpClient,
            authRepository = authRepo,
            settingsRepository = settingsRepo,
            draftDao = db.draftDao(),
            nowMs = fakeClock::get,
            scope = repoScope,
        )
        val detailRepo = ProbeThreadDetailRepository(numericPids = true)
        val detailVm = ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        var snackbarHostRef: SnackbarHostState? = null
        try {
            // 预置旧草稿以验证发送成功自动清除 draft
            runBlocking {
                db.draftDao().upsert(DraftEntity(DraftKey.reply("1001").value, content = "已有回复草稿", updatedAt = fakeClock.get()))
            }

            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }.also { snackbarHostRef = it }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", fid = 2, title = "冷却探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // 1. 首轮发送：先挂起 POST 校验 0ms 进入「正在发送…」且连点不重复提交
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("第一条正式回复")
            composeRule.waitForIdle()

            val sendLatch = CountDownLatch(1)
            activeLatch = sendLatch
            postGate.set(1)
            composeRule.onNodeWithText("发送").performClick()
            waitUntilRealCondition(5_000L, { "POST 请求未到达 MockWebServer: targets=$recordedTargets" }) {
                postAttempts.get() == 1
            }
            composeRule.onNodeWithContentDescription("正在发送…").assertIsDisplayed().assertIsNotEnabled()
            // 发送中再次点击与直接调用 submit() 均不发第 2 个请求
            composeRule.onNodeWithContentDescription("正在发送…").performClick()
            composeRule.runOnIdle { qrVm.submit() }

            postGate.set(0)
            sendLatch.countDown()
            waitUntilRealCondition(5_000L, { "首次回复发送后未收到成功回执" }) {
                composeRule.onAllNodesWithText("回复已发送").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("首次回复只能有 1 次 POST", 1, postAttempts.get())
            assertEquals("非末页回复成功不得移动阅读页码", 1, detailVm.uiState.value.page)
            assertNull("发送成功后 reply:1001 草稿必须清除", runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() })
            composeRule.runOnIdle { snackbarHostRef?.currentSnackbarData?.dismiss() }
            composeRule.waitForIdle()

            // 2. 进入 30 秒冷却期：记录 MockWebServer 基线请求数，连续点击与直接 submit() 必须 0 次额外请求
            val baselineRequestCount = server.requestCount
            val baselineTargetsSize = recordedTargets.size
            assertTrue(baselineRequestCount >= 2)
            assertEquals(30, postRepo.getRemainingCooldownSeconds())

            composeRule.onNodeWithText("回复").performClick()
            advanceVirtualAndMainLooper(500L)
            composeRule.waitForIdle()
            val cooldownNodes = composeRule.onAllNodes(hasContentDescription("30 秒后可发送")).fetchSemanticsNodes()
            assertTrue(
                "30秒冷却按钮未进入可视区域: nodes=${cooldownNodes.map { it.boundsInRoot }}, state=${qrVm.uiState.value}, tree=${composeRule.onRoot().printToString()}",
                cooldownNodes.isNotEmpty(),
            )
            val cooldownBtn = composeRule.onNodeWithContentDescription("30 秒后可发送").assertIsDisplayed().assertIsNotEnabled()
            // 冷却期间允许继续编辑正文
            composeRule.onNode(hasSetTextAction()).performTextReplacement("冷却期内编辑的回复")
            composeRule.waitForIdle()

            repeat(8) { cooldownBtn.performClick() }
            repeat(5) { composeRule.runOnIdle { qrVm.submit() } }
            val directCooldownRes = runBlocking {
                postRepo.submitPost(PostSubmitRequest.ReplyThread("1001", "直调冷却拦截"))
            }
            assertTrue(directCooldownRes is PostSubmitResult.CooldownActive)
            composeRule.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals("30 秒冷却期初段连点发送必须 0 次额外 MockWebServer 请求", baselineRequestCount, server.requestCount)
            assertEquals(baselineTargetsSize, recordedTargets.size)

            // 推进时钟至冷却过半（15 秒），继续连点发送仍严格 0 次额外请求
            fakeClock.addAndGet(15_000L)
            advanceVirtualAndMainLooper(1_100L)
            val midCooldownBtn = composeRule.onNodeWithContentDescription("15 秒后可发送").assertIsDisplayed().assertIsNotEnabled()
            repeat(8) { midCooldownBtn.performClick() }
            repeat(3) { composeRule.runOnIdle { qrVm.submit() } }
            composeRule.waitForIdle()
            assertEquals("冷却过半连点发送仍必须 0 次额外 MockWebServer 请求", baselineRequestCount, server.requestCount)
            assertEquals(1, postAttempts.get())

            // 3. 冷却 30 秒到期后自动恢复可发送，且保留冷却期编辑的内容
            fakeClock.addAndGet(15_001L)
            advanceVirtualAndMainLooper(1_100L)
            composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithText("冷却期内编辑的回复").assertIsDisplayed()

            // 验证失败留面板保留输入且不启动冷却，修改后重试成功
            failNextPost.set(1)
            composeRule.onNodeWithText("发送").performClick()
            waitUntilRealCondition(5_000L, { "站点业务错误未在面板内原地提示" }) {
                composeRule.onAllNodesWithText("发送失败").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("冷却期内编辑的回复").assertIsDisplayed()
            assertEquals(0, postRepo.getRemainingCooldownSeconds())

            composeRule.onNodeWithText("重试").performClick()
            waitUntilRealCondition(5_000L, { "失败重试后未发送成功: postAttempts=${postAttempts.get()}" }) {
                postAttempts.get() == 3 &&
                    composeRule.onAllNodesWithText("回复已发送").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(3, postAttempts.get())
            composeRule.runOnIdle { snackbarHostRef?.currentSnackbarData?.dismiss() }
            composeRule.waitForIdle()

            // 4. 只看楼主过滤模式下仍可直接引用回复，不阻断操作（§7.1）
            fakeClock.addAndGet(30_001L)
            advanceVirtualAndMainLooper(1_100L)
            composeRule.onNodeWithContentDescription("只看楼主").performClick()
            advanceVirtualAndMainLooper(300L)
            composeRule.waitForIdle()
            assertEquals("只看楼主按钮点击后 filter 未切换到 AuthorOnly: filter=${detailVm.uiState.value.filter}, tree=${composeRule.onRoot().printToString()}", net.jejer.hipda.feature.threaddetail.ThreadDetailFilterUi.AuthorOnly("100"), detailVm.uiState.value.filter)
            composeRule.onNodeWithContentDescription("退出只看").assertIsDisplayed()
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("(101)", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("过滤模式下引用回复")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("发送").performClick()
            waitUntilRealCondition(5_000L, { "只看楼主过滤模式下引用回复未发送成功: attempts=${postAttempts.get()}, targets=$recordedTargets, qr=${qrVm.uiState.value}" }) {
                postAttempts.get() == 4
            }
            assertTrue("引用回复未携带 repquote=101: $recordedTargets", recordedTargets.any { it.contains("repquote=101") })
        } finally {
            repoScope.cancel()
            runCatching { server.close() }
            db.close()
        }
    }

    // =========================================================================
    // 第 2 轮新增 (7)：749×559dp 弹 250dp / 265dp 键盘、恢复草稿、错误、三插槽全绑定自适应预算
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe7_749x559_ime250And265_draftErrorAllSlotsBound_inputVisibleSendCompleteAndSlotsClickable() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        runBlocking {
            db.draftDao().upsert(
                DraftEntity(
                    key = DraftKey.reply("1001").value,
                    content = "749x559已恢复草稿正文",
                    updatedAt = fakeClock.get(),
                ),
            )
        }
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val basePostRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val failNextSubmit = AtomicBoolean(true)
        val postRepo = object : PostRepository by basePostRepo {
            override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult =
                if (failNextSubmit.compareAndSet(true, false)) {
                    PostSubmitResult.SiteError("服务端返回错误")
                } else {
                    basePostRepo.submitPost(request, priority)
                }
        }
        val gateway = createOfflineGateway()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), ProbeSearchRepository(), settingsRepo, authRepo, gateway)
        val owner = ProbeViewModelStoreOwner(
            hostVm = hostVm,
            forumVm = forumVm,
            detailVmFactory = { ThreadDetailViewModel(detailRepo, gateway, authRepo, ProbeFavoriteRepository(), settingsRepo) },
            quickReplyVmFactory = { QuickReplyViewModel(postRepo, authRepo, fakeClock::get) },
        )

        var hostView: android.view.View? = null
        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        val fakeCtx = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = object : QuickReplyEmojiPanelRenderer {
                @Composable
                override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
                    Column(modifier.fillMaxWidth().padding(8.dp)) {
                        Text("假表情面板高度=${host.keyboardHeightDp.value.toInt()}")
                        Button(onClick = { host.onInsertEmoji("[微笑]") }) { Text("插入假表情") }
                        Button(onClick = host.onBackspace) { Text("假表情退格") }
                    }
                }
            },
        )

        try {
            composeRule.setContent {
                hostView = LocalView.current
                val activityCtx = LocalContext.current
                val wrappedCtx = remember(activityCtx) {
                    object : ContextWrapper(activityCtx) {
                        override fun getApplicationContext(): Context = fakeCtx.applicationContext
                    }
                }
                CompositionLocalProvider(LocalContext provides wrappedCtx) {
                    ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                            NextAppRoot(
                                settingsFlow = settingsRepo.settings,
                                currentAccountFlow = authRepo.currentAccount,
                                smsUnreadCountFlow = MutableStateFlow(0),
                                notifyUnreadCountFlow = MutableStateFlow(0),
                                imageLoader = createOfflineImageLoader(),
                                hostViewModel = hostVm,
                                forumViewModel = forumVm,
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            waitUntilRealCondition(3_000L, { "749x559 打开后未恢复草稿" }) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }

            // 触发内联错误状态，叠加「恢复草稿 + 错误 + 三插槽」极限预算场景
            composeRule.onNodeWithText("发送").performClick()
            waitUntilRealCondition(3_000L, { "提交失败未触发错误状态" }) {
                composeRule.onAllNodesWithText("发送失败").fetchSemanticsNodes().isNotEmpty()
            }

            val density = composeRule.density
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

            fun verify749x559ConstrainedState(imeDp: Int, statusTopDp: Int) {
                val imePx = with(density) { imeDp.dp.roundToPx() }
                val statusPx = with(density) { statusTopDp.dp.roundToPx() }
                composeRule.runOnUiThread {
                    ViewCompat.dispatchApplyWindowInsets(
                        requireNotNull(hostView),
                        WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                            .build(),
                    )
                }
                composeRule.waitForIdle()
                advanceVirtualAndMainLooper(200L)
                composeRule.waitForIdle()

                val sheetBounds = composeRule.onNode(
                    SemanticsMatcher.expectValue(HiGlassMarker, true) and
                        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                assertTrue("IME=${imeDp}dp/status=${statusTopDp}dp 面板应在三栏右栏内", sheetBounds.left >= rootBounds.width * 0.40f)
                assertTrue("IME=${imeDp}dp/status=${statusTopDp}dp 面板不得超出右边界", sheetBounds.right <= rootBounds.right + 1f)
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 底边应贴合键盘上方",
                    abs(sheetBounds.bottom - (rootBounds.bottom - imePx)) <= 2f,
                )

                val inputNode = composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
                val inputBounds = inputNode.fetchSemanticsNode().boundsInRoot
                val textLayouts = mutableListOf<TextLayoutResult>()
                inputNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(textLayouts) }
                assertTrue("IME=${imeDp}dp/status=${statusTopDp}dp 输入框需有文本布局", textLayouts.isNotEmpty() && textLayouts.first().lineCount >= 1)
                val firstLineHeightPx = textLayouts.first().getLineBottom(0) - textLayouts.first().getLineTop(0)
                assertTrue("首行高度需大于 0", firstLineHeightPx > 0f)
                val containerHeightPx = (inputNode.fetchSemanticsNode().layoutInfo.parentInfo?.height ?: 0).toFloat()
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 输入框高 ${inputBounds.height}px(外框=${containerHeightPx}px) 需完整容纳 >=1 行 ($firstLineHeightPx)",
                    inputBounds.height >= firstLineHeightPx - 1f && containerHeightPx >= with(density) { 40.dp.toPx() } - 1f,
                )
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 输入框宽度过窄 ${inputBounds.width}px",
                    inputBounds.width >= with(density) { 72.dp.toPx() },
                )
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 输入框不得裁切溢出面板",
                    inputBounds.top >= sheetBounds.top - 1f && inputBounds.bottom <= sheetBounds.bottom + 1f,
                )

                val sendNode = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled()
                sendNode.assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(48.dp)
                val sendBounds = sendNode.fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 发送按钮不得裁切或越出面板: send=$sendBounds, sheet=$sheetBounds",
                    sendBounds.left >= sheetBounds.left - 1f &&
                        sendBounds.right <= sheetBounds.right + 1f &&
                        sendBounds.top >= sheetBounds.top - 1f &&
                        sendBounds.bottom <= sheetBounds.bottom + 1f,
                )

                val insertImageNode = composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed()
                insertImageNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val imgBounds = insertImageNode.fetchSemanticsNode().boundsInRoot
                assertTrue("插入图片按钮须有点击动作", composeRule.onAllNodes(hasContentDescription("插入图片") and hasClickAction()).fetchSemanticsNodes().size == 1)
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 插入图片不得裁切越界",
                    imgBounds.top >= sheetBounds.top - 1f && imgBounds.bottom <= sheetBounds.bottom + 1f && imgBounds.right <= sendBounds.left + 1f,
                )

                val emojiNode = composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
                emojiNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val emojiBounds = emojiNode.fetchSemanticsNode().boundsInRoot
                assertTrue("表情按钮须有点击动作", composeRule.onAllNodes(hasContentDescription("表情") and hasClickAction()).fetchSemanticsNodes().size == 1)
                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 表情按钮不得裁切越界",
                    emojiBounds.top >= sheetBounds.top - 1f && emojiBounds.bottom <= sheetBounds.bottom + 1f && emojiBounds.right <= sendBounds.left + 1f,
                )

                assertTrue(
                    "IME=${imeDp}dp/status=${statusTopDp}dp 错误提示应在面板内或退避到 Snackbar 可见",
                    composeRule.onAllNodesWithText("发送失败").fetchSemanticsNodes().isNotEmpty(),
                )
            }

            verify749x559ConstrainedState(imeDp = 250, statusTopDp = 0)
            verify749x559ConstrainedState(imeDp = 265, statusTopDp = 0)
            verify749x559ConstrainedState(imeDp = 265, statusTopDp = 24)

            // 在 265dp 键盘实测点击「表情」可正常切换并插入表情，随后点击「插入图片」完成带正文交接
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("插入假表情").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertEquals(1, capturedHandoffs.size)
            assertEquals("749x559已恢复草稿正文[微笑]", capturedHandoffs.single().text)
            assertTrue(capturedHandoffs.single().openImagePickerImmediately)
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 第 2 轮新增 (8)：快速上拉越阈值（插槽绑定）展开全屏且 FullEditorHandoff 正确、不重弹草稿提示
    // =========================================================================

    @Test
    fun probe8_fastUpwardDragPastThreshold_withSlotsBound_expandsToFullEditorNoDraftSavedSnackbar() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        val recordedEvents = CopyOnWriteArrayList<QuickReplyEvent>()
        val fakeEntryContext = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = object : QuickReplyEmojiPanelRenderer {
                @Composable
                override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
                    Text("假表情面板", modifier)
                }
            },
        )
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        eventScope.launch { qrVm.events.collect { recordedEvents.add(it) } }

        try {
            composeRule.setContent {
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                val baseCtx = LocalContext.current
                val boundCtx = remember(baseCtx) {
                    object : ContextWrapper(baseCtx) {
                        override fun getApplicationContext(): Context = fakeEntryContext.applicationContext
                    }
                }
                CompositionLocalProvider(
                    LocalContext provides boundCtx,
                    LocalPaneSnackbarController provides controller,
                ) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", fid = 2, title = "快速上拉展开探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // A. 长按 2 楼引用打开面板，先校验未越阈值小幅慢拉（20dp < 56dp）不展开
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p102", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                down(center)
                moveBy(Offset(0f, -with(composeRule.density) { 20.dp.toPx() }), delayMillis = 220L)
                up()
            }
            composeRule.waitForIdle()
            assertEquals(0, capturedHandoffs.size)
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

            // B. 输入正文与选区 [2, 8)，通过顶部把手单批快速上拉越阈值（80dp >= 56dp）展开全屏
            val handleText = "快速上拉展开正文ABCD"
            val inputNode = composeRule.onNode(hasSetTextAction())
            inputNode.performTextReplacement(handleText)
            inputNode.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 8, true) }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                down(center)
                moveBy(Offset(0f, -with(composeRule.density) { 80.dp.toPx() }), delayMillis = 25L)
                up()
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()

            assertEquals("把手上拉越阈值必须触发 1 次全屏交接", 1, capturedHandoffs.size)
            val handleHandoff = capturedHandoffs.single()
            assertEquals("1001", handleHandoff.tid)
            assertEquals(2, handleHandoff.fid)
            assertEquals(handleText, handleHandoff.text)
            assertEquals(2, handleHandoff.selectionStart)
            assertEquals(8, handleHandoff.selectionEnd)
            assertEquals("p102", handleHandoff.quotePid)
            assertEquals("坛友2", handleHandoff.quoteAuthor)
            assertEquals(2, handleHandoff.quoteFloor)
            assertFalse(handleHandoff.openImagePickerImmediately)
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            assertTrue("展开全屏不得发送 DraftSaved 事件: $recordedEvents", recordedEvents.none { it is QuickReplyEvent.DraftSaved })

            // C. 再长按 3 楼引用，验证语境标题行单批快速上拉越阈值同样完整交接且不重弹草稿提示
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p103", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            composeRule.waitForIdle()

            val ctxRowText = "标题行上拉展开EFGH"
            val ctxInput = composeRule.onNode(hasSetTextAction())
            ctxInput.performTextReplacement(ctxRowText)
            ctxInput.performSemanticsAction(SemanticsActions.SetSelection) { it(4, 4, true) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("引用第 3 楼 · 坛友3").performTouchInput {
                down(center)
                moveBy(Offset(0f, -with(composeRule.density) { 80.dp.toPx() }), delayMillis = 25L)
                up()
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()

            assertEquals("语境标题行上拉越阈值必须触发第 2 次全屏交接", 2, capturedHandoffs.size)
            val ctxHandoff = capturedHandoffs.last()
            assertEquals("1001", ctxHandoff.tid)
            assertEquals(2, ctxHandoff.fid)
            assertEquals(ctxRowText, ctxHandoff.text)
            assertEquals(4, ctxHandoff.selectionStart)
            assertEquals(4, ctxHandoff.selectionEnd)
            assertEquals("p103", ctxHandoff.quotePid)
            assertEquals("坛友3", ctxHandoff.quoteAuthor)
            assertEquals(3, ctxHandoff.quoteFloor)
            assertFalse(ctxHandoff.openImagePickerImmediately)
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            assertTrue("语境标题行展开全屏不得发送 DraftSaved 事件: $recordedEvents", recordedEvents.none { it is QuickReplyEvent.DraftSaved })
        } finally {
            eventScope.cancel()
            db.close()
        }
    }

    // =========================================================================
    // 第 3 轮新增 (9)：入场弹簧进行中同帧单批轻扫（收起/回弹/上拉展开）、退场中再拖、展开中按返回
    // =========================================================================

    @Test
    fun probe9a_midEnterSpring_singleBatchFlicks_dismissReboundExpand_andBackScrimValid() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        val fakeEntryContext = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = object : QuickReplyEmojiPanelRenderer {
                @Composable
                override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
                    Text("假表情面板", modifier)
                }
            },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        var onBackCount = 0

        try {
            composeRule.setContent {
                backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val snackbarHost = remember { SnackbarHostState() }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                val baseCtx = LocalContext.current
                val boundCtx = remember(baseCtx) {
                    object : ContextWrapper(baseCtx) {
                        override fun getApplicationContext(): Context = fakeEntryContext.applicationContext
                    }
                }
                CompositionLocalProvider(
                    LocalContext provides boundCtx,
                    LocalPaneSnackbarController provides controller,
                ) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", fid = 2, title = "入场打断探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = { onBackCount += 1 },
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            fun sheetPosYOrNull() = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyDescendant(hasContentDescription("收起回复面板")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y

            // 预先测得面板完全展开静止态的精确 top Y（positionInRoot.y 不受根边界底边裁切掩盖）
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            val openTopPx = requireNotNull(sheetPosYOrNull())
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()

            fun openUntilMidEnterSpring(maxOffsetFromOpenTopPx: Float) {
                composeRule.mainClock.autoAdvance = false
                try {
                    composeRule.onNodeWithText("回复").performSemanticsAction(SemanticsActions.OnClick)
                    val seen = mutableListOf<Float>()
                    var frames = 0
                    while (frames < 40) {
                        Snapshot.sendApplyNotifications()
                        composeRule.mainClock.advanceTimeByFrame()
                        val posY = sheetPosYOrNull()
                        val hasHandle = composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isNotEmpty()
                        if (hasHandle && posY != null) {
                            val delta = posY - openTopPx
                            seen.add(delta)
                            if (delta > 6f && delta <= maxOffsetFromOpenTopPx) break
                        }
                        frames++
                    }
                    val midY = requireNotNull(sheetPosYOrNull()) { "未捕获到快速回复入场中间帧 (seen=$seen)" }
                    val midDelta = midY - openTopPx
                    assertTrue("入场弹簧应在半途 (delta=$midDelta, max=$maxOffsetFromOpenTopPx, seen=$seen)", midDelta > 6f && midDelta <= maxOffsetFromOpenTopPx)
                } catch (t: Throwable) {
                    composeRule.mainClock.autoAdvance = true
                    throw t
                }
            }

            fun verifyBackAndScrimStillWork() {
                composeRule.onNodeWithText("回复").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                advanceUntilVirtualCondition(2_000L) {
                    composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitForIdle()
                assertEquals("QuickReply 开启时 Back 应只关面板不得穿透详情", 0, onBackCount)

                composeRule.onNodeWithText("回复").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
                composeRule.onRoot().performTouchInput {
                    val scrimPt = Offset(rootBounds.width * 0.5f, rootBounds.top + with(density) { 40.dp.toPx() })
                    down(scrimPt)
                    up()
                }
                advanceUntilVirtualCondition(2_000L) {
                    composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitForIdle()
            }

            // 1. 入场弹簧进行中同帧单批快速下拉收起
            openUntilMidEnterSpring(maxOffsetFromOpenTopPx = with(density) { 180.dp.toPx() })
            try {
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, with(density) { 120.dp.toPx() }), delayMillis = 25L)
                    up()
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            assertEquals(0, capturedHandoffs.size)
            verifyBackAndScrimStillWork()

            // 2. 入场弹簧进行中同帧微拉（不越阈值、低速）-> 回弹落回 Open(translationY == 0)，之后返回与暗罩仍有效
            openUntilMidEnterSpring(maxOffsetFromOpenTopPx = with(density) { 40.dp.toPx() })
            try {
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, with(density) { 14.dp.toPx() }), delayMillis = 220L)
                    up()
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            val reboundedY = requireNotNull(sheetPosYOrNull()) { "微拉回弹后面板必须保持展开" }
            assertTrue("微拉回弹后必须落回 Open ($reboundedY vs $openTopPx)", abs(reboundedY - openTopPx) <= 2f)
            composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            verifyBackAndScrimStillWork()

            // 3. 入场弹簧进行中同帧单批快速上拉展开全屏
            openUntilMidEnterSpring(maxOffsetFromOpenTopPx = with(density) { 42.dp.toPx() })
            try {
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, -with(density) { 100.dp.toPx() }), delayMillis = 25L)
                    up()
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            assertEquals("入场弹簧中快速上拉必须触发全屏交接", 1, capturedHandoffs.size)
            assertFalse(capturedHandoffs.single().openImagePickerImmediately)
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            verifyBackAndScrimStillWork()
        } finally {
            db.close()
        }
    }

    @Test
    fun probe9b_midDismissDrag_andMidExpandBack_reachTerminalAndKeepBackScrimValid() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        val detailRepo = ProbeThreadDetailRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val settingsRepo = ProbeSettingsRepository()
        val postRepo = ProbePostRepository(db.draftDao(), fakeClock)
        val detailVm = ThreadDetailViewModel(detailRepo, createOfflineGateway(), authRepo, ProbeFavoriteRepository(), settingsRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, fakeClock::get)

        val capturedHandoffs = mutableListOf<FullEditorHandoff>()
        val fakeEntryContext = FakeSlotActivityContextWrapper(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = FullEditorLauncher { capturedHandoffs.add(it) },
            emojiPanelRenderer = object : QuickReplyEmojiPanelRenderer {
                @Composable
                override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
                    Text("假表情面板", modifier)
                }
            },
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        var onBackCount = 0
        val recordedEvents = CopyOnWriteArrayList<QuickReplyEvent>()
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        eventScope.launch { qrVm.events.collect { recordedEvents.add(it) } }

        try {
            var snackbarHostRef: SnackbarHostState? = null
            composeRule.setContent {
                backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val snackbarHost = remember { SnackbarHostState() }.also { snackbarHostRef = it }
                val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
                val baseCtx = LocalContext.current
                val boundCtx = remember(baseCtx) {
                    object : ContextWrapper(baseCtx) {
                        override fun getApplicationContext(): Context = fakeEntryContext.applicationContext
                    }
                }
                CompositionLocalProvider(
                    LocalContext provides boundCtx,
                    LocalPaneSnackbarController provides controller,
                ) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                            Box(Modifier.fillMaxSize()) {
                                ThreadDetailPane(
                                    args = ThreadNavArgs("1001", fid = 2, title = "退场与展开打断探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = { onBackCount += 1 },
                                    onOpenLogin = {},
                                    viewModel = detailVm,
                                    quickReplyViewModel = qrVm,
                                )
                                SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            fun sheetPosYOrNull() = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyDescendant(hasContentDescription("收起回复面板")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y

            fun verifyBackAndScrimAfterTransition() {
                composeRule.runOnIdle { snackbarHostRef?.currentSnackbarData?.dismiss() }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("回复").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                advanceUntilVirtualCondition(2_000L) {
                    composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitForIdle()
                assertEquals(0, onBackCount)

                composeRule.onNodeWithText("回复").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
                composeRule.onRoot().performTouchInput {
                    val scrimPt = Offset(rootBounds.width * 0.5f, rootBounds.top + with(density) { 40.dp.toPx() })
                    down(scrimPt)
                    up()
                }
                advanceUntilVirtualCondition(2_000L) {
                    composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitForIdle()
            }

            // 1. 退场动画中再拖（Dismissing 期间向反方向上拖及再下拖 + 按返回）：必须继续落到终态收起，且 Back/暗罩仍有效
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("退场再拖草稿")
            composeRule.waitForIdle()
            val dismissStartTopPx = requireNotNull(sheetPosYOrNull())

            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()
                val midDismissTopPx = requireNotNull(sheetPosYOrNull()) { "退场第 3 帧面板应仍在退场动画中" }
                assertTrue("退场第 3 帧面板应已开始下移 ($midDismissTopPx > $dismissStartTopPx)", midDismissTopPx > dismissStartTopPx + 2f)

                // 退场期间反向大距离上拖 + 再次下拖 + 按返回
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, -with(density) { 140.dp.toPx() }), delayMillis = 25L)
                    up()
                }
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, with(density) { 80.dp.toPx() }), delayMillis = 25L)
                    up()
                }
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                composeRule.mainClock.advanceTimeByFrame()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            assertEquals("退场再拖不得误触发全屏展开", 0, capturedHandoffs.size)
            assertEquals(0, onBackCount)
            waitUntilRealCondition(2_000L, { "退场后草稿未保存" }) {
                runBlocking { db.draftDao().observe(DraftKey.reply("1001")).first() }?.content == "退场再拖草稿"
            }
            verifyBackAndScrimAfterTransition()

            // 2. 展开动画中按返回（+ 点暗罩）：必须吞掉返回、完成全屏交接、不弹草稿且不穿透详情；重开后 Back/暗罩仍有效
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                    hasAnyDescendant(hasText("p103", substring = true)),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
            waitUntilRealCondition(2_000L, { "引用重开后未加载完成" }) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(hasSetTextAction()).performTextReplacement("展开动画中按返回正文")
            composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetSelection) { it(1, 5, true) }
            composeRule.waitForIdle()
            while (snackbarHostRef?.currentSnackbarData != null) {
                composeRule.runOnIdle { snackbarHostRef?.currentSnackbarData?.dismiss() }
                composeRule.waitForIdle()
            }
            val draftSavedBeforeExpand = recordedEvents.count { it is QuickReplyEvent.DraftSaved }

            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, -with(density) { 80.dp.toPx() }), delayMillis = 25L)
                    up()
                }
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                assertTrue("展开第 1 帧面板应仍在 Expanding 动画中", composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isNotEmpty())

                // 展开动画进行中连按两次 Back 与点击暗罩
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onRoot().performTouchInput {
                    val scrimPt = Offset(rootBounds.width * 0.5f, rootBounds.top + with(density) { 40.dp.toPx() })
                    down(scrimPt)
                    up()
                }
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
                composeRule.mainClock.advanceTimeByFrame()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodes(hasContentDescription("收起回复面板")).fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()

            assertEquals("展开动画中按返回仍应精确触发 1 次全屏交接", 1, capturedHandoffs.size)
            val handoff = capturedHandoffs.single()
            assertEquals("展开动画中按返回正文", handoff.text)
            assertEquals(1, handoff.selectionStart)
            assertEquals(5, handoff.selectionEnd)
            assertEquals("p103", handoff.quotePid)
            assertEquals(0, onBackCount)
            assertEquals("展开中按返回不得再发 DraftSaved 事件", draftSavedBeforeExpand, recordedEvents.count { it is QuickReplyEvent.DraftSaved })
            composeRule.onNodeWithText("已保存草稿").assertDoesNotExist()
            verifyBackAndScrimAfterTransition()
        } finally {
            eventScope.cancel()
            db.close()
        }
    }

    // =========================================================================
    // 离线测试替身与辅助方法
    // =========================================================================

    private fun createInMemoryDb(): HiDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Application>(),
        HiDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun countGlassMarkers(): Int =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true)
            .fetchSemanticsNodes().size

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

    private fun gbkHtmlResponse(html: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/html; charset=gbk")
        .body(Buffer().write(html.toByteArray(Charset.forName("GB18030"))))
        .build()

    private fun prePostReplyHtml(formhash: String, quotePid: String?): String = """
        <!DOCTYPE html><html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body>
        <div id="umenu"><cite><a href="space.php?uid=42" class="noborder">HiProbeUser</a></cite></div>
        <form id="postform" method="post" action="post.php?action=reply&tid=1001&replysubmit=yes">
          <input type="hidden" name="formhash" value="$formhash">
          ${if (quotePid != null) """<input type="hidden" name="noticeauthor" value="u2"><input type="hidden" name="noticeauthormsg" value="msg"><input type="hidden" name="noticetrimstr" value="[quote]quoted[/quote]">""" else ""}
          <textarea name="message">${if (quotePid != null) "[quote]quoted[/quote]" else ""}</textarea>
        </form>
        </body></html>
    """.trimIndent()

    private fun redirectedThreadLastPageHtml(tid: String, newPid: String): String = """
        <!DOCTYPE html><html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head>
        <body id="viewthread">
        <div id="umenu"><cite><a href="space.php?uid=42" class="noborder">HiProbeUser</a></cite></div>
        <div id="nav"><a href="index.php">4D4Y</a> &raquo; <a href="forumdisplay.php?fid=2">Discovery</a> &raquo; 帖子</div>
        <div id="wrap"><div class="forumcontrol"><div class="pages"><strong>10</strong></div></div>
        <div id="postlist">
          <div id="post_$newPid">
            <table id="pid$newPid"><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=42">HiProbeUser</a></div></td>
              <td class="postcontent"><div class="postinfo"><strong><em>150</em><sup>#</sup></strong><div class="authorinfo"><em>发表于 2026-11-26 12:00</em></div></div>
              <div class="defaultpost"><div class="postmessage"><div id="postmessage_$newPid" class="t_msgfont">新发表的回复</div></div></div></td>
            </tr></tbody></table>
          </div>
        </div></div></body></html>
    """.trimIndent()

    @Suppress("RestrictedApi")
    private fun withWindowLayoutFlow(layoutFlow: Flow<WindowLayoutInfo>, block: () -> Unit) {
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

    private class FakeFoldingFeature(
        override val bounds: AndroidRect,
        override val orientation: FoldingFeature.Orientation,
        override val state: FoldingFeature.State,
        override val isSeparating: Boolean,
        override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.NONE,
    ) : FoldingFeature

    private fun verticalHingeLayoutInfo(
        leftDp: Float,
        rightDp: Float,
        heightDp: Float,
        density: Float,
        isSeparating: Boolean,
    ): WindowLayoutInfo = WindowLayoutInfo(
        listOf(
            FakeFoldingFeature(
                bounds = AndroidRect(
                    (leftDp * density).toInt(),
                    0,
                    (rightDp * density).toInt(),
                    (heightDp * density).toInt(),
                ),
                orientation = FoldingFeature.Orientation.VERTICAL,
                state = if (isSeparating) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT,
                isSeparating = isSeparating,
            ),
        ),
    )

    private class FakeSlotActivityContextWrapper(
        base: Context,
        private val fullEditorLauncher: FullEditorLauncher?,
        private val emojiPanelRenderer: QuickReplyEmojiPanelRenderer?,
    ) : Application(), GeneratedComponentManagerHolder {
        private val entryPoint = object : GeneratedComponent, ComposeSlotsEntryPoint {
            override fun fullEditorLauncher(): Optional<FullEditorLauncher> = Optional.ofNullable(fullEditorLauncher)
            override fun emojiPanelRenderer(): Optional<QuickReplyEmojiPanelRenderer> = Optional.ofNullable(emojiPanelRenderer)
        }

        init {
            attachBaseContext(base)
        }

        override fun getApplicationContext(): Context = this
        override fun generatedComponent(): Any = entryPoint
        override fun componentManager() = throw UnsupportedOperationException()
    }

    private class ProbePostRepository(
        private val draftDao: net.jejer.hipda.data.local.DraftDao,
        private val clockMs: AtomicLong,
    ) : PostRepository {
        private val _cooldownUntilMs = MutableStateFlow(0L)
        override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

        override fun getRemainingCooldownSeconds(): Int {
            val delta = _cooldownUntilMs.value - clockMs.get()
            return if (delta <= 0L) 0 else ((delta + 999L) / 1_000L).toInt()
        }

        override suspend fun fetchPrePost(request: PrePostRequest, priority: AccessPriority): PrePostResult =
            PrePostResult.Success(PrePostInfo("fh", "42", null, null, "", "", false, null, null, null, null, emptyList(), emptyList(), null, emptyMap(), probeMeta()))

        override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult {
            _cooldownUntilMs.value = clockMs.get() + 30_000L
            request.draftKey?.let { draftDao.delete(it.value) }
            return PostSubmitResult.Success("1001")
        }

        override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = draftDao.observe(key.value)

        override suspend fun saveDraft(
            key: DraftKey,
            subject: String,
            typeId: String,
            content: String,
            quotePid: String?,
            attachments: List<String>,
        ) {
            if (subject.isBlank() && content.isBlank() && attachments.isEmpty()) return
            draftDao.upsert(DraftEntity(key.value, subject, typeId, content, quotePid, attachments, clockMs.get()))
        }

        override suspend fun deleteDraft(key: DraftKey) = draftDao.delete(key.value)
    }

    private class ProbeViewModelStoreOwner(
        private val hostVm: NextAppHostViewModel,
        private val forumVm: ForumViewModel,
        private val detailVmFactory: () -> ThreadDetailViewModel,
        private val quickReplyVmFactory: () -> QuickReplyViewModel,
    ) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumVm as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> detailVmFactory() as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> quickReplyVmFactory() as T
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
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
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
        override fun isFavorite(tid: String) = false
        override fun isAttention(tid: String) = false
        override suspend fun getList(kind: FavoriteKind, page: Int, priority: AccessPriority) = throw UnsupportedOperationException()
        override suspend fun syncCache(kind: FavoriteKind, maxPages: Int, priority: AccessPriority) = FavoriteSyncResult.Success(kind, emptySet(), 1, 1, 0L)
        override suspend fun add(kind: FavoriteKind, tid: String, priority: AccessPriority) = FavoriteMutationResult.Success(kind, tid, "ok")
        override suspend fun remove(kind: FavoriteKind, tid: String, priority: AccessPriority) = FavoriteMutationResult.Success(kind, tid, "ok")
        override suspend fun removeByForm(kind: FavoriteKind, tid: String, formhash: String, priority: AccessPriority) = FavoriteMutationResult.Success(kind, tid, "ok")
    }

    private class ProbeThreadListRepository : ThreadListRepository {
        private val flows = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        private fun stateOf(q: ThreadListQuery) = flows.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }
        override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
        override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
            val summaries = (1..15).map { i ->
                val tid = (1000 + i).toString()
                ThreadSummary(tid, "探针论坛帖子 $tid", null, null, false, false, false, "作者$i", "$i", null, "2026-11-26", "刚刚", "回复$i", "10", "100", false, 2)
            }
            val items = summaries.map { ThreadListItem(it, false) }
            stateOf(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, probeMeta(), isLoading = false)
            return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 2, true, 1, probeMeta())
        }
        override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
        override fun observeThreadList(query: ThreadListQuery) = stateOf(query)
        override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
    }

    private class ProbeSearchRepository : SearchRepository {
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) = SearchResult.Success(page, false, SearchPage("s1", 1, emptyList(), probeMeta()))
        override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) = SearchResult.Success(1, false, SearchPage("s1", 1, emptyList(), probeMeta()))
        override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
        override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
        override suspend fun recordSearchHistory(query: String) = emptyList<String>()
        override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
        override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
        override suspend fun clearSearchHistory() = Unit
        override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
    }

    private class ProbeThreadDetailRepository(
        private val lastPage: Int = 10,
        private val imagePages: Map<Pair<String, Int>, ContentBlock.Image> = emptyMap(),
        private val numericPids: Boolean = false,
    ) : ThreadDetailRepository {
        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val pageImage = imagePages[tid to page]
            val baseFloors = (1..15).map { idx ->
                val floorNo = (page - 1) * 15 + idx
                val pid = if (numericPids) "${page * 100 + idx}" else "p${page * 100 + idx}"
                val blocks = buildList {
                    if (idx == 1 && pageImage != null) add(pageImage)
                    add(ContentBlock.Text(listOf(InlineSpan.Plain("帖子 $tid 第 $page 页 第 $floorNo 楼 ($pid) 正文"))))
                }
                PostFloor(pid, floorNo, page, if (floorNo == 1) "100" else "$idx", if (floorNo == 1) "楼主" else "坛友$idx", null, "2026-11-26 10:00", false, null, null, null, blocks)
            }
            val filtered = when (filter) {
                ThreadDetailFilter.All -> baseFloors
                is ThreadDetailFilter.AuthorOnly -> baseFloors.filter { it.uid == (filter.authorId ?: "100") }
                is ThreadDetailFilter.UserOnly -> baseFloors.filter { it.uid == filter.uid }
            }
            return ThreadDetailLoadResult.Success(ThreadDetailPage(tid, 2, "探针帖子 $tid", page, lastPage, filtered, lastPage * 15, probeMeta(), page < lastPage), filter, threadAuthorId = "100")
        }
        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, lastPage, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>) = floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>) = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult = throw UnsupportedOperationException()
    }

    private fun createOfflineImageLoader(): ImageLoader {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                SuccessResult(ColorImage(AndroidColor.LTGRAY, 320, 240), chain.request, DataSource.MEMORY_CACHE)
            })
        }.build()
    }

    private class ProbeSmsRepository : SmsRepository {
        override val cooldownUntilMs = MutableStateFlow(0L)
        override fun getRemainingCooldownSeconds() = 0
        override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority) =
            MessageResult.Success(SmsListPage(listOf(SmsConversationItem("100", "消息探针", null, "今天", "摘要", false)), probeMeta(), true))
        override suspend fun getConversationDetail(uid: String, priority: AccessPriority) =
            MessageResult.Success(SmsDetailPage("42", "HiProbeUser", "abc", emptyList(), probeMeta(), true))
        override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority) = SmsSendOutcome.Sent
        override suspend fun clearConversation(uid: String, priority: AccessPriority) = SmsClearOutcome.Cleared
    }

    private class ProbeNotifyRepository : NotifyRepository {
        override val smsUnreadCount = MutableStateFlow(0)
        override val notifyUnreadCount = MutableStateFlow(0)
        override val notifications = MutableStateFlow<List<NotifyEntry>>(emptyList())
        override suspend fun getNotifications(priority: AccessPriority) = MessageResult.Success(NotifyFeed(emptyList(), probeMeta(), true))
        override suspend fun markNotificationRead(key: String) = Unit
        override suspend fun markAllNotificationsRead() = Unit
        override suspend fun pollUnreadCounts(priority: AccessPriority) = NotificationPollResult.Success(UnreadSnapshot(0, 0))
        override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
        override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) = Unit
        override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) = Unit
    }

    private class ProbeBrowseHistoryDao : BrowseHistoryDao {
        override fun observeRecent(limit: Int) = MutableStateFlow<List<BrowseHistoryEntity>>(emptyList())
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
        override suspend fun syncBlacklist(priority: AccessPriority) = BlacklistSyncResult.Success(BlacklistSnapshot("42", emptySet(), null), true, "abc")
        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority) = BlacklistMutationResult.Success(username, emptySet())
        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority) = BlacklistMutationResult.Success(username, emptySet())
    }

    private class ProbeUserRepository : UserRepository {
        override suspend fun getUserProfile(uid: String, priority: AccessPriority) = UserProfileResult.Success(UserProfile(uid, "楼主", true, null, "abc", "", probeMeta()))
        override suspend fun getUserThreads(uid: String, page: Int, searchId: String?, priority: AccessPriority) = UserThreadsResult.Success(uid, page, SearchPage("s1", 1, emptyList(), probeMeta()))
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
            formhash = "fh_probe_1",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )
    }
}
