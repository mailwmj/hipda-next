package net.jejer.hipda.verify

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.io.File
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
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
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.compose.ComposeSlotsEntryPoint
import net.jejer.hipda.feature.compose.FullEditorLauncher
import net.jejer.hipda.feature.compose.FullEditorRequestBus
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelRenderer
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.compose.calculateEmojiPanelHeight
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.feature.compose.emoji.calculateEmojiGridColumns
import net.jejer.hipda.feature.compose.emoji.resolveEmojiPanelHeight
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.messages.MessagesPaneRendererImpl
import net.jejer.hipda.feature.messages.SmsConversationScreen
import net.jejer.hipda.feature.messages.SmsConversationViewModel
import net.jejer.hipda.feature.mine.SearchMinePaneRendererImpl
import net.jejer.hipda.feature.settings.SettingsProfilePaneRendererImpl
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.message.SmsMessageItem
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
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * V-E7 独立真实链路探针（基于 V-E7/E7 任务卡、产品设计 §4.2/§5/§6.3/§7.3/§7.4/§10.1 与 merge 后源码契约编写；
 * 100% 离线，绝不访问真实论坛，绝不修改被测生产代码或原实现测试）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class E7ProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun initOkHttp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    // =========================================================================
    // 探针 1 + (a)：三处真实宿主逐帧 IME ↔ 表情 底缘零位移 + 减动效 0ms
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe1a_nextAppRoot_quickReply_411dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyQuickReplyFrameByFrameImeEmojiSwap(windowLabel = "411x891", navBarDp = 24, imeMaxDp = 280)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe1a_extra_nextAppRoot_quickReply_749dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyQuickReplyFrameByFrameImeEmojiSwap(windowLabel = "749x559", navBarDp = 0, imeMaxDp = 250)
    }

    private fun verifyQuickReplyFrameByFrameImeEmojiSwap(windowLabel: String, navBarDp: Int, imeMaxDp: Int) {
        val harness = ProbeHarness()
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()

            // 打开帖子并打开快速回复
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootView = requireNotNull(hostView)

            // 展开键盘至稳态 imeMaxDp
            dispatchWindowInsets(rootView, imeDp = imeMaxDp, navBarDp = navBarDp)
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(250L)
            composeRule.waitForIdle()

            fun quickReplySheetBottomDp(): Float {
                val bounds = composeRule.onNode(
                    SemanticsMatcher.expectValue(HiGlassMarker, true) and
                        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                return with(density) { bounds.bottom.toDp().value }
            }

            fun quickReplyInputBottomDp(): Float {
                val bounds = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                return with(density) { bounds.bottom.toDp().value }
            }

            val baselineSheetBottom = quickReplySheetBottomDp()
            val baselineInputBottom = quickReplyInputBottomDp()

            composeRule.mainClock.autoAdvance = false
            try {
                // 1. IME -> 表情（逐帧隐藏 IME）
                composeRule.onNodeWithContentDescription("表情").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(windowLabel, "QR tapEmoji frame0 sheet", baselineSheetBottom, quickReplySheetBottomDp())
                assertFrameEdgeStable(windowLabel, "QR tapEmoji frame0 input", baselineInputBottom, quickReplyInputBottomDp())
                composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
                composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

                val hideSteps = listOf(imeMaxDp - 60, imeMaxDp - 130, 60, 0).map { it.coerceAtLeast(0) }
                for (imeStep in hideSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck(
                        onStep = { stepLabel ->
                            assertFrameEdgeStable(windowLabel, "QR hide($imeStep/$stepLabel) sheet", baselineSheetBottom, quickReplySheetBottomDp())
                            assertFrameEdgeStable(windowLabel, "QR hide($imeStep/$stepLabel) input", baselineInputBottom, quickReplyInputBottomDp())
                        },
                    )
                }

                // 2. 表情 -> 键盘（点键盘图标，逐帧重现 IME）
                composeRule.onNodeWithContentDescription("键盘").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(windowLabel, "QR tapKbd frame0 sheet", baselineSheetBottom, quickReplySheetBottomDp())
                assertFrameEdgeStable(windowLabel, "QR tapKbd frame0 input", baselineInputBottom, quickReplyInputBottomDp())
                composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()

                val showSteps = listOf(60, (imeMaxDp / 2), imeMaxDp - 40, imeMaxDp)
                for (imeStep in showSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck(
                        onStep = { stepLabel ->
                            assertFrameEdgeStable(windowLabel, "QR show($imeStep/$stepLabel) sheet", baselineSheetBottom, quickReplySheetBottomDp())
                            assertFrameEdgeStable(windowLabel, "QR show($imeStep/$stepLabel) input", baselineInputBottom, quickReplyInputBottomDp())
                        },
                    )
                }

                // 3. 再次切换到表情后，通过点击输入框切回键盘（同样底缘零位移）
                composeRule.onNodeWithContentDescription("表情").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                for (imeStep in hideSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(windowLabel, "QR hide2($imeStep/$stepLabel) sheet", baselineSheetBottom, quickReplySheetBottomDp())
                    }
                }
                composeRule.onNode(hasSetTextAction()).performTouchInput {
                    down(center)
                    up()
                }
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                for (imeStep in showSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(windowLabel, "QR tapInput show($imeStep/$stepLabel) sheet", baselineSheetBottom, quickReplySheetBottomDp())
                    }
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe1b_nextAppRoot_fullEditor_fullScreen411dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyFullEditorFrameByFrameImeEmojiSwap(
            label = "FullScreen-411x891",
            navBarDp = 24,
            imeMaxDp = 280,
            useCollapsedSplitForRightPane = false,
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun probe1b_extra_nextAppRoot_fullEditor_rightPane1280dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyFullEditorFrameByFrameImeEmojiSwap(
            label = "RightPane-1280x800",
            navBarDp = 24,
            imeMaxDp = 280,
            useCollapsedSplitForRightPane = false,
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal933dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyFullEditorFrameByFrameImeEmojiSwap(
            label = "CenteredModal-933x696",
            navBarDp = 0,
            imeMaxDp = 260,
            useCollapsedSplitForRightPane = false,
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal749dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyFullEditorFrameByFrameImeEmojiSwap(
            label = "CenteredModal-749x559",
            navBarDp = 0,
            imeMaxDp = 250,
            useCollapsedSplitForRightPane = false,
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal640dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyFullEditorFrameByFrameImeEmojiSwap(
            label = "CenteredModal-640x1024",
            navBarDp = 24,
            imeMaxDp = 280,
            useCollapsedSplitForRightPane = false,
        )
    }

    private fun verifyFullEditorFrameByFrameImeEmojiSwap(
        label: String,
        navBarDp: Int,
        imeMaxDp: Int,
        useCollapsedSplitForRightPane: Boolean,
    ) {
        val harness = ProbeHarness()
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()

            if (useCollapsedSplitForRightPane) {
                composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
                composeRule.waitForIdle()
            }

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootView = requireNotNull(hostView)

            dispatchWindowInsets(rootView, imeDp = imeMaxDp, navBarDp = navBarDp)
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(250L)
            composeRule.waitForIdle()

            fun editorToolbarBottomDp(): Float {
                val imgBtn = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
                val toolbarRowBounds = imgBtn.layoutInfo.parentInfo?.let { parentLayout ->
                    // Row wraps IconButton(48dp) with top=4dp, bottom=4dp (or navBarBottom+4dp)
                    val coords = parentLayout.coordinates
                    val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                    pos.y + coords.size.height
                } ?: imgBtn.boundsInRoot.bottom
                return with(density) { toolbarRowBounds.toDp().value }
            }

            fun editorInsertImageIconBottomDp(): Float {
                val bounds = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode().boundsInRoot
                return with(density) { bounds.bottom.toDp().value }
            }

            val baselineToolbarBottom = editorToolbarBottomDp()
            val baselineIconBottom = editorInsertImageIconBottomDp()

            composeRule.mainClock.autoAdvance = false
            try {
                // 1. IME -> 表情
                composeRule.onNodeWithContentDescription("表情").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(label, "Editor tapEmoji frame0 toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                assertFrameEdgeStable(label, "Editor tapEmoji frame0 icon", baselineIconBottom, editorInsertImageIconBottomDp())
                composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
                composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

                val hideSteps = listOf(imeMaxDp - 60, imeMaxDp - 130, 60, 0).map { it.coerceAtLeast(0) }
                for (imeStep in hideSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(label, "Editor hide($imeStep/$stepLabel) toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                        assertFrameEdgeStable(label, "Editor hide($imeStep/$stepLabel) icon", baselineIconBottom, editorInsertImageIconBottomDp())
                    }
                }

                // 2. 表情 -> 键盘
                composeRule.onNodeWithContentDescription("键盘").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(label, "Editor tapKbd frame0 toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                assertFrameEdgeStable(label, "Editor tapKbd frame0 icon", baselineIconBottom, editorInsertImageIconBottomDp())
                composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()

                val showSteps = listOf(60, imeMaxDp / 2, imeMaxDp - 40, imeMaxDp)
                for (imeStep in showSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(label, "Editor show($imeStep/$stepLabel) toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                        assertFrameEdgeStable(label, "Editor show($imeStep/$stepLabel) icon", baselineIconBottom, editorInsertImageIconBottomDp())
                    }
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe1c_nextAppRoot_smsConversation_411dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifySmsFrameByFrameImeEmojiSwap(windowLabel = "SMS-411x891", isThreePane = false, navBarDp = 24, imeMaxDp = 280)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe1c_extra_nextAppRoot_smsConversation_749dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifySmsFrameByFrameImeEmojiSwap(windowLabel = "SMS-749x559", isThreePane = true, navBarDp = 0, imeMaxDp = 250)
    }

    private fun verifySmsFrameByFrameImeEmojiSwap(windowLabel: String, isThreePane: Boolean, navBarDp: Int, imeMaxDp: Int) {
        val harness = ProbeHarness()
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()

            if (isThreePane) {
                composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(net.jejer.hipda.ui.shell.ShellSidebarEntry.Sms)).performClick()
            } else {
                composeRule.onNodeWithText("消息").performClick()
            }
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("消息探针").performClick()
            advanceVirtualAndMainLooper(250L)

            val density = composeRule.density
            val rootView = requireNotNull(hostView)

            dispatchWindowInsets(rootView, imeDp = imeMaxDp, navBarDp = navBarDp)
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(250L)
            composeRule.waitForIdle()

            fun smsInputBottomDp(): Float {
                val bounds = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                return with(density) { bounds.bottom.toDp().value }
            }

            fun smsInputBarBottomDp(): Float {
                val emojiBtn = composeRule.onNode(hasContentDescription("表情") or hasContentDescription("键盘")).fetchSemanticsNode()
                val rowParent = emojiBtn.layoutInfo.parentInfo?.parentInfo
                val bottomPx = rowParent?.let {
                    it.coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero).y + it.coordinates.size.height
                } ?: emojiBtn.boundsInRoot.bottom
                return with(density) { bottomPx.toDp().value }
            }

            val baselineInputBottom = smsInputBottomDp()
            val baselineBarBottom = smsInputBarBottomDp()

            composeRule.mainClock.autoAdvance = false
            try {
                // 1. IME -> 表情
                composeRule.onNodeWithContentDescription("表情").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(windowLabel, "SMS tapEmoji frame0 bar", baselineBarBottom, smsInputBarBottomDp())
                assertFrameEdgeStable(windowLabel, "SMS tapEmoji frame0 input", baselineInputBottom, smsInputBottomDp())
                composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
                val emojiBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                if (isThreePane) {
                    val leftDp = with(density) { emojiBounds.left.toDp().value }
                    val rightDp = with(density) { emojiBounds.right.toDp().value }
                    assertTrue("749dp 三栏短消息表情面板左缘($leftDp)不得跨分隔线(338)", leftDp >= 337.5f)
                    assertTrue("749dp 三栏短消息表情面板右缘($rightDp)不得越界(749)", rightDp <= 749.5f)
                    composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
                }

                val hideSteps = listOf(imeMaxDp - 60, imeMaxDp - 130, 60, 0).map { it.coerceAtLeast(0) }
                for (imeStep in hideSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(windowLabel, "SMS hide($imeStep/$stepLabel) bar", baselineBarBottom, smsInputBarBottomDp())
                        assertFrameEdgeStable(windowLabel, "SMS hide($imeStep/$stepLabel) input", baselineInputBottom, smsInputBottomDp())
                    }
                }

                // 2. 表情 -> 键盘
                composeRule.onNodeWithContentDescription("键盘").performClick()
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFrameEdgeStable(windowLabel, "SMS tapKbd frame0 bar", baselineBarBottom, smsInputBarBottomDp())
                assertFrameEdgeStable(windowLabel, "SMS tapKbd frame0 input", baselineInputBottom, smsInputBottomDp())

                val showSteps = listOf(60, imeMaxDp / 2, imeMaxDp - 40, imeMaxDp)
                for (imeStep in showSteps) {
                    dispatchWindowInsets(rootView, imeDp = imeStep, navBarDp = navBarDp)
                    stepFrameAndCheck { stepLabel ->
                        assertFrameEdgeStable(windowLabel, "SMS show($imeStep/$stepLabel) bar", baselineBarBottom, smsInputBarBottomDp())
                        assertFrameEdgeStable(windowLabel, "SMS show($imeStep/$stepLabel) input", baselineInputBottom, smsInputBottomDp())
                    }
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun probe1d_reducedMotion_zeroScale_swapsInSingleFrame() = withAnimatorDurationScale(0f) {
        val harness = ProbeHarness()
        try {
            setContent { harness.RenderAppRoot() }
            waitForIdle()

            onNodeWithText("探针论坛帖子 1001").performClick()
            mainClock.advanceTimeBy(250L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            waitForIdle()

            onNodeWithText("回复").performClick()
            waitForIdle()

            mainClock.autoAdvance = false
            try {
                // 打开表情面板 1 帧到位
                onNodeWithContentDescription("表情").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                mainClock.advanceTimeByFrame()

                val panelBounds1 = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                mainClock.advanceTimeByFrame()
                val panelBounds2 = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                    .fetchSemanticsNode().boundsInRoot
                assertEquals("减动效下表情面板应 1 帧落定高度", panelBounds1.height, panelBounds2.height, 0.5f)
                onNodeWithContentDescription("键盘").assertIsDisplayed()

                // 切换分类（默认 -> 酷猴）0ms 稳定
                onNodeWithContentDescription("酷猴表情").performClick()
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onNodeWithContentDescription("表情 {:2_41:}").assertIsDisplayed()
            } finally {
                mainClock.autoAdvance = true
            }
        } finally {
            harness.close()
        }
    }

    // =========================================================================
    // 探针 2 + (b) + (d) + (e)：无键盘高度回退限高、三栏边界、玻璃预算与 749×559 矮屏可达性
    // =========================================================================

    @Test
    fun probe2a_fallbackHeights_pureFunction_matchesSpecSection74() {
        assertEquals(280.dp, resolveEmojiPanelHeight(0.dp, 891.dp))
        assertEquals(280.dp, calculateEmojiPanelHeight(0.dp, 891.dp))
        assertEquals(270.dp, resolveEmojiPanelHeight(0.dp, 600.dp))
        assertEquals(216.dp, resolveEmojiPanelHeight(0.dp, 480.dp))
        assertEquals((479f * 0.35f).dp.value, resolveEmojiPanelHeight(0.dp, 479.dp).value, 0.01f)
        assertEquals((411f * 0.35f).dp.value, resolveEmojiPanelHeight(0.dp, 411.dp).value, 0.01f)
        assertEquals(245.dp, resolveEmojiPanelHeight(245.dp, 411.dp))
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe2a_realUi_noImeMemory_411x891_exact280dpCap_allThreeHosts() {
        verifyFallbackPanelHeightAcrossThreeHosts(expectedHeightDp = 280f, navBarDp = 24)
    }

    @Test
    @Config(qualifiers = "w600dp-h600dp-xhdpi")
    fun probe2a_realUi_noImeMemory_600x600SplitScreen_exact270dpCap_allThreeHosts() {
        verifyFallbackPanelHeightAcrossThreeHosts(expectedHeightDp = 270f, navBarDp = 0)
    }

    @Test
    @Config(qualifiers = "w891dp-h479dp-xhdpi")
    fun probe2a_realUi_noImeMemory_891x479ShortThreshold_exact35PercentCap_allThreeHosts() {
        verifyFallbackPanelHeightAcrossThreeHosts(expectedHeightDp = 479f * 0.35f, navBarDp = 0)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun probe2a_realUi_noImeMemory_891x411ShortLandscape_exact35PercentCap_noOverflow_allThreeHosts() {
        verifyFallbackPanelHeightAcrossThreeHosts(expectedHeightDp = 411f * 0.35f, navBarDp = 24)
    }

    private fun verifyFallbackPanelHeightAcrossThreeHosts(expectedHeightDp: Float, navBarDp: Int) {
        val harness = ProbeHarness()
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()
            val density = composeRule.density
            val rootView = requireNotNull(hostView)
            // 仅设置导航栏 inset，保持 imeBottom = 0（无键盘高度记忆：首次打开/外接键盘/分屏）
            dispatchWindowInsets(rootView, imeDp = 0, navBarDp = navBarDp)
            composeRule.waitForIdle()
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

            // 1. QuickReplySheet
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            val qrPanelBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val qrSlotContainerHeightDp = with(density) { (rootBounds.bottom - qrPanelBounds.top).toDp().value }
            assertEquals("QuickReply 无键盘记忆高度应严格吻合 §7.4", expectedHeightDp, qrSlotContainerHeightDp, 1.5f)
            assertNoOverflowInsideWindow(rootBounds, qrPanelBounds, composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot)

            // 2. FullEditor
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            val editorPanelBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val editorContainerBounds = composeRule.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复"),
            ).fetchSemanticsNode().boundsInRoot
            val editorSlotHeightDp = with(density) { (editorContainerBounds.bottom - editorPanelBounds.top).toDp().value }
            assertEquals("FullEditor 无键盘记忆高度应严格吻合 §7.4", expectedHeightDp, editorSlotHeightDp, 1.5f)
            assertNoOverflowInsideWindow(
                rootBounds,
                editorPanelBounds,
                composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode().boundsInRoot,
            )

            // 验证外接键盘场景：切回键盘后若无 IME 到达，450ms 超时自动恢复 Closed 不长期滞留空白占位
            composeRule.onNodeWithContentDescription("键盘").performClick()
            advanceVirtualAndMainLooper(600L)
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()

            // 关闭 FullEditor 回到帖子详情，在窄/横/分屏下按返回回论坛再去消息页
            composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(11_000L)
            composeRule.waitForIdle()

            // 若当前不是侧栏（单栏），按一次返回回论坛列表，再切到消息页
            if (composeRule.onAllNodesWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Sms)).fetchSemanticsNodes().isEmpty()) {
                composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Messages)).performClick()
            } else {
                composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Sms)).performClick()
            }
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("消息探针").performClick()
            advanceVirtualAndMainLooper(250L)

            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            val smsPanelBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val smsSlotHeightDp = with(density) { (rootBounds.bottom - smsPanelBounds.top).toDp().value }
            assertEquals("SMS 无键盘记忆高度应严格吻合 §7.4", expectedHeightDp, smsSlotHeightDp, 1.5f)
            assertNoOverflowInsideWindow(rootBounds, smsPanelBounds, composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe2d_crossHostSessionKeyboardMemoryReused_acrossAllThreeHosts() {
        val harness = ProbeHarness()
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()
            val density = composeRule.density
            val rootView = requireNotNull(hostView)
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

            // 在快速回复学得 240dp 净键盘高（总 inset = 240 + 24 navBar = 264dp，区别于 280dp 默认上限）
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            dispatchWindowInsets(rootView, imeDp = 264, navBarDp = 24)
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            dispatchWindowInsets(rootView, imeDp = 0, navBarDp = 24)
            advanceVirtualAndMainLooper(250L)
            composeRule.waitForIdle()

            val qrPanelTop = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            val qrSlotDp = with(density) { (rootBounds.bottom - qrPanelTop).toDp().value }
            assertEquals("QuickReply 应按净键盘高 240+24=264dp 占位", 264f, qrSlotDp, 1.5f)

            // 展开到 FullEditor（IME=0 下），应直接复用同高度桶记忆的 240dp + 24dp = 264dp（而非 280+24=304dp）
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            val editorPanelTop = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            val editorContainerBottom = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复"))
                .fetchSemanticsNode().boundsInRoot.bottom
            val editorSlotDp = with(density) { (editorContainerBottom - editorPanelTop).toDp().value }
            assertEquals("FullEditor 应跨宿主复用 240+24=264dp 键盘记忆", 264f, editorSlotDp, 1.5f)

            // 关闭 FullEditor 进入 SMS 对话（IME=0 下），应继续复用 240+24=264dp
            composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Messages)).performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("消息探针").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            val smsPanelTop = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            val smsSlotDp = with(density) { (rootBounds.bottom - smsPanelTop).toDp().value }
            assertEquals("SMS 对话应跨宿主复用 240+24=264dp 键盘记忆", 264f, smsSlotDp, 1.5f)
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe2b_threePane749dp_boundaries_adaptiveCols_andGlassBudget() {
        verifyThreePaneEmojiBoundariesColumnsAndGlass(expectedRightPaneMinLeftDp = 337.5f, expectedWindowMaxRightDp = 749.5f, expectedCols = 8)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun probe2b_threePane933dp_boundaries_adaptiveCols_andGlassBudget() {
        verifyThreePaneEmojiBoundariesColumnsAndGlass(expectedRightPaneMinLeftDp = 484.5f, expectedWindowMaxRightDp = 933.5f, expectedCols = 9)
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun probe2b_extra_tablet1280dp_rightPaneFullEditorEmoji_14Cols_andGlassBudget() {
        val harness = ProbeHarness()
        try {
            composeRule.setContent { harness.RenderAppRoot() }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            val density = composeRule.density
            val emojiPanelNode = composeRule.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"),
            ).assertIsDisplayed().fetchSemanticsNode()
            val leftDp = with(density) { emojiPanelNode.boundsInRoot.left.toDp().value }
            val widthDp = with(density) { emojiPanelNode.boundsInRoot.width.toDp().value }
            assertTrue("1280dp 右栏编辑器内表情面板左缘($leftDp)应 >= 600dp", leftDp >= 599f)
            assertTrue("1280dp 右栏编辑器内表情面板宽($widthDp)应 <= 680dp", widthDp <= 681f)
            assertAdaptiveGridCellSizesAndColumns(expectedCols = calculateEmojiGridColumns(widthDp.dp))

            val markersInEmoji = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("表情面板本身不得含任何 HiGlassMarker", 0, markersInEmoji.size)
            assertGlassBudgetWithinLimits(maxMarkers = 1, maxSources = 1, maxPasses = 5)
        } finally {
            harness.close()
        }
    }

    private fun verifyThreePaneEmojiBoundariesColumnsAndGlass(
        expectedRightPaneMinLeftDp: Float,
        expectedWindowMaxRightDp: Float,
        expectedCols: Int,
    ) {
        val harness = ProbeHarness()
        try {
            composeRule.setContent { harness.RenderAppRoot() }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            val density = composeRule.density
            val detailBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文"))
                .fetchSemanticsNode().boundsInRoot
            val emojiBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val leftDp = with(density) { emojiBounds.left.toDp().value }
            val rightDp = with(density) { emojiBounds.right.toDp().value }
            val widthDp = with(density) { emojiBounds.width.toDp().value }

            assertTrue("右栏表情面板左缘($leftDp)不得跨分隔线(${detailBounds.left})", emojiBounds.left >= detailBounds.left - 0.5f)
            assertTrue("右栏表情面板左缘($leftDp)应 >= $expectedRightPaneMinLeftDp", leftDp >= expectedRightPaneMinLeftDp)
            assertTrue("右栏表情面板右缘($rightDp)不得越界($expectedWindowMaxRightDp)", rightDp <= expectedWindowMaxRightDp)

            assertAdaptiveGridCellSizesAndColumns(expectedCols = expectedCols)
            assertEquals(expectedCols, calculateEmojiGridColumns(widthDp.dp))

            val markersInEmoji = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("表情面板纯色表面必须 0 HiGlassMarker", 0, markersInEmoji.size)
            assertGlassBudgetWithinLimits(maxMarkers = 3, maxSources = 2, maxPasses = 5)
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe2c_supplementE_749x559_imeUpWithDraftAndError_quickReplyEmojiButtonReachableAndWorks() {
        val db = createInMemoryDb()
        val fakeClock = AtomicLong(1_000_000L)
        kotlinx.coroutines.runBlocking {
            db.draftDao().upsert(DraftEntity(DraftKey.reply("1001").value, content = "749x559矮屏草稿", updatedAt = fakeClock.get()))
        }
        val basePostRepo = DbPostRepository(db.draftDao(), fakeClock)
        val failOnce = AtomicInteger(1)
        val postRepo = object : PostRepository by basePostRepo {
            override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult =
                if (failOnce.compareAndSet(1, 0)) PostSubmitResult.SiteError("服务端返回错误")
                else basePostRepo.submitPost(request, priority)
        }
        val harness = ProbeHarness(customPostRepo = postRepo, customDb = db)
        try {
            var hostView: View? = null
            composeRule.setContent {
                hostView = LocalView.current
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()
            val rootView = requireNotNull(hostView)
            val density = composeRule.density

            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("发送").performClick()
            advanceVirtualAndMainLooper(250L)

            // 覆盖正常高度态 (250dp) 与矮屏极限让位态 (390dp + 24dp statusBar -> netHeight = 145dp < 180dp, compact row!)
            for ((imeDp, statusDp) in listOf(250 to 0, 320 to 0, 390 to 24)) {
                dispatchWindowInsets(rootView, imeDp = imeDp, navBarDp = 0, statusBarDp = statusDp)
                composeRule.waitForIdle()
                advanceVirtualAndMainLooper(220L)
                composeRule.waitForIdle()

                val sheetBounds = composeRule.onNode(
                    SemanticsMatcher.expectValue(HiGlassMarker, true) and
                        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val emojiBtn = composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
                emojiBtn.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val emojiBounds = emojiBtn.fetchSemanticsNode().boundsInRoot

                assertTrue(
                    "749x559 IME=${imeDp}dp/status=${statusDp}dp 表情按钮($emojiBounds)必须在面板($sheetBounds)内可达且不被截断",
                    emojiBounds.left >= sheetBounds.left - 1f &&
                        emojiBounds.right <= sendBounds.left + 1f &&
                        emojiBounds.top >= sheetBounds.top - 1f &&
                        emojiBounds.bottom <= sheetBounds.bottom + 1f,
                )
            }

            // 在 250dp 键盘态实际点击「表情」验证可打开面板、插入表情并切回键盘
            dispatchWindowInsets(rootView, imeDp = 250, navBarDp = 0, statusBarDp = 0)
            composeRule.waitForIdle()
            advanceVirtualAndMainLooper(220L)
            composeRule.waitForIdle()

            val sheetBeforeBottom = with(density) {
                composeRule.onNode(
                    SemanticsMatcher.expectValue(HiGlassMarker, true) and
                        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot.bottom.toDp().value
            }

            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("表情 :)").performClick()
            composeRule.waitForIdle()
            val inputText = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("749x559矮屏草稿:)", inputText)

            val sheetAfterBottom = with(density) {
                composeRule.onNode(
                    SemanticsMatcher.expectValue(HiGlassMarker, true) and
                        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot.bottom.toDp().value
            }
            assertEquals("749x559 切换表情后面板底缘应零位移", sheetBeforeBottom, sheetAfterBottom, 1f)
            composeRule.onNodeWithContentDescription("键盘").performClick()
            composeRule.waitForIdle()
        } finally {
            harness.close()
        }
    }

    // =========================================================================
    // 探针 3 + (c)：三处光标/选区插入、回删、深浅色切图、C1b#3 与完整返回优先级 + R-M7-G4 S2
    // =========================================================================

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun probe3a_allThreeHosts_cursorSelectionInsert_backspace_darkLightThemeSwitch_c1b3() {
        // 1. EmojiMap C1b 数据完整性校验（21 + 16 + 24 = 61，深浅色均有资源，酷猴/呆呆深浅资源区分）
        assertEquals(3, EmojiMap.groups.size)
        assertEquals(listOf(21, 16, 24), EmojiMap.groups.map { it.codes.size })
        val allCodes = EmojiMap.groups.flatMap { it.codes }
        assertEquals(61, allCodes.size)
        for (code in allCodes) {
            assertNotNull("Light drawable missing for $code", EmojiMap.drawableFor(code, dark = false))
            assertNotNull("Dark drawable missing for $code", EmojiMap.drawableFor(code, dark = true))
        }
        EmojiMap.groups[1].codes.forEach { code ->
            assertNotEquals("酷猴 $code 深浅色切图必须不同", EmojiMap.drawableFor(code, false), EmojiMap.drawableFor(code, true))
        }
        EmojiMap.groups[2].codes.forEach { code ->
            assertNotEquals("呆呆 $code 深浅色切图必须不同", EmojiMap.drawableFor(code, false), EmojiMap.drawableFor(code, true))
        }

        val harness = ProbeHarness()
        try {
            var observedDarkTheme: Boolean? = null
            composeRule.setContent {
                harness.RenderAppRoot { observedDarkTheme = HiTheme.state.darkTheme }
            }
            composeRule.waitForIdle()

            // ---- Host 1: QuickReplySheet 光标中间插入、选区替换、回删、深浅色切图、C1b#3 ----
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()

            val qrInput = composeRule.onNode(hasSetTextAction())
            qrInput.performTextReplacement("前后")
            qrInput.performTextInputSelection(TextRange(1, 1))
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()

            // C1b#3: 验证 Tab 使用 strings_emoji.xml 文案，严禁出现原始 EmojiGroup.name（"默认"/"酷猴"/"呆呆"）
            val tabNodes = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
            assertEquals(
                listOf("默认表情", "酷猴表情", "呆呆表情"),
                tabNodes.map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() },
            )
            EmojiMap.groups.forEach { g ->
                assertEquals(0, composeRule.onAllNodesWithContentDescription(g.name).fetchSemanticsNodes().size)
            }

            // 插入默认 :) -> "前:)后" 光标在 3
            composeRule.onNodeWithContentDescription("表情 :)").performClick()
            composeRule.waitForIdle()
            assertEquals("前:)后", readEditableText(qrInput))
            assertEquals(TextRange(3, 3), readSelection(qrInput))

            // 切酷猴 tab，验证深浅色切图保持当前分类与输入，插入 {:2_41:} -> "前:){:2_41:}后"
            composeRule.onNodeWithContentDescription("酷猴表情").performClick()
            composeRule.waitForIdle()
            assertEquals(false, observedDarkTheme)
            composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(themeMode = ThemeMode.DARK) } }
            composeRule.waitForIdle()
            assertEquals(true, observedDarkTheme)
            // 深色切换后仍保持酷猴分类选中与输入文本
            val monkeyTabSelected = composeRule.onNodeWithContentDescription("酷猴表情").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Selected)
            assertEquals(true, monkeyTabSelected)
            composeRule.onNodeWithContentDescription("表情 {:2_41:}").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertEquals("前:){:2_41:}后", readEditableText(qrInput))
            assertEquals(TextRange(11, 11), readSelection(qrInput))
            // 恢复浅色
            composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(themeMode = ThemeMode.LIGHT) } }
            composeRule.waitForIdle()

            // 选区替换 [1, 11) + 呆呆 {:3_57:} -> "前{:3_57:}后"，再键盘继续输入 "尾" 验证光标留在 9 -> "前{:3_57:}尾后"
            qrInput.performTextInputSelection(TextRange(1, 11))
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("呆呆表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:3_57:}").performClick()
            composeRule.waitForIdle()
            assertEquals("前{:3_57:}后", readEditableText(qrInput))
            assertEquals(TextRange(9, 9), readSelection(qrInput))

            // 表情面板退格删去 '}' -> "前{:3_57:后"
            composeRule.onNodeWithContentDescription("删除").performClick()
            composeRule.waitForIdle()
            assertEquals("前{:3_57:后", readEditableText(qrInput))
            assertEquals(TextRange(8, 8), readSelection(qrInput))

            // ---- Host 2: FullEditorScreen 光标中间插入、选区替换、回删 ----
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            val editorBody = composeRule.onNodeWithContentDescription("回复 楼主")
            editorBody.performSemanticsAction(SemanticsActions.RequestFocus)
            editorBody.performTextReplacement("甲乙")
            editorBody.performTextInputSelection(TextRange(1, 1))
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 :)").performClick()
            composeRule.waitForIdle()
            assertEquals("甲:)乙", readEditableText(editorBody))
            assertEquals(TextRange(3, 3), readSelection(editorBody))

            composeRule.onNodeWithContentDescription("酷猴表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:2_41:}").performClick()
            composeRule.waitForIdle()
            assertEquals("甲:){:2_41:}乙", readEditableText(editorBody))
            assertEquals(TextRange(11, 11), readSelection(editorBody))

            editorBody.performTextInputSelection(TextRange(1, 11))
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("呆呆表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:3_57:}").performClick()
            composeRule.waitForIdle()
            assertEquals("甲{:3_57:}乙", readEditableText(editorBody))
            assertEquals(TextRange(9, 9), readSelection(editorBody))

            composeRule.onNodeWithContentDescription("删除").performClick()
            composeRule.waitForIdle()
            assertEquals("甲{:3_57:乙", readEditableText(editorBody))
            assertEquals(TextRange(8, 8), readSelection(editorBody))

            // 退出编辑器与帖子详情，进入 SMS 对话页
            composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(11_000L)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription("返回").onLast().performClick()
            composeRule.waitForIdle()

            // ---- Host 3: SmsConversationScreen 光标中间插入、选区替换、回删 ----
            composeRule.onNodeWithText("消息").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("消息探针").performClick()
            advanceVirtualAndMainLooper(250L)

            val smsInput = composeRule.onNode(hasSetTextAction())
            smsInput.performTextReplacement("左右")
            smsInput.performTextInputSelection(TextRange(1, 1))
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 :)").performClick()
            composeRule.waitForIdle()
            assertEquals("左:)右", readEditableText(smsInput))
            assertEquals(TextRange(3, 3), readSelection(smsInput))

            composeRule.onNodeWithContentDescription("酷猴表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:2_41:}").performClick()
            composeRule.waitForIdle()
            assertEquals("左:){:2_41:}右", readEditableText(smsInput))
            assertEquals(TextRange(11, 11), readSelection(smsInput))

            smsInput.performTextInputSelection(TextRange(1, 11))
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("呆呆表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:3_57:}").performClick()
            composeRule.waitForIdle()
            assertEquals("左{:3_57:}右", readEditableText(smsInput))
            assertEquals(TextRange(9, 9), readSelection(smsInput))

            composeRule.onNodeWithContentDescription("删除").performClick()
            composeRule.waitForIdle()
            assertEquals("左{:3_57:右", readEditableText(smsInput))
            assertEquals(TextRange(8, 8), readSelection(smsInput))
        } finally {
            harness.close()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe3b_backPriority_drawer_emoji_quickReply_fullEditor_positionStack_sms_andViewerUnchanged() {
        val imageBlock = ContentBlock.Image(
            fullUrl = "https://mock.local/forum/attachments/e7_probe.jpg",
            thumbUrl = "https://mock.local/forum/attachments/e7_probe_thumb.jpg",
            fileSizeBytes = 4096L,
        )
        val harness = ProbeHarness(detailRepo = ProbeThreadDetailRepository(imagePages = mapOf(("1001" to 1) to imageBlock)))
        var backDispatcher: OnBackPressedDispatcher? = null
        try {
            composeRule.setContent {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                harness.RenderAppRoot()
            }
            composeRule.waitForIdle()

            // 1. 打开帖子并先验证看图 > PositionStack 优先级不变
            composeRule.onNodeWithText("探针论坛帖子 1001").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("末页").performClick()
            advanceVirtualAndMainLooper(200L)
            composeRule.onNodeWithContentDescription("第 10 页，共 10 页，点按展开跳页").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("首页").performClick()
            advanceVirtualAndMainLooper(200L)
            val detailVm = harness.detailVm("1001")
            assertEquals(1, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)

            // 看图打开再按返回：仅关闭看图，不穿透 PositionStack
            waitUntilRealCondition(4_000L, { "帖子首楼图片未加载" }) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceUntilVirtualCondition(3_000L) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            advanceUntilVirtualCondition(3_000L) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)

            // 2. QuickReply + EmojiPanel + 240dp 抽屉 + PositionStack 返回优先级：抽屉 > EmojiPanel > QuickReply > PositionStack
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("返回优先草稿")
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            // 打开 240dp 抽屉
            composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

            // Back 1: 仅关抽屉
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复 楼主")).assertIsDisplayed()

            // Back 2: 先关表情面板，QuickReply 与输入保留，PositionStack 不动
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            assertEquals("返回优先草稿", readEditableText(composeRule.onNode(hasSetTextAction())))
            assertTrue(detailVm.uiState.value.canPopPositionStack)

            // 验证点把手关闭 QuickReply 时同步重置表情展开态（重开 QuickReply 表情面板不得残留打开）
            composeRule.onNodeWithContentDescription("收起回复面板").performClick()
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().isEmpty()
            }
            composeRule.mainClock.advanceTimeBy(11_000L)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()

            // 再打开表情面板，测试连续按两次 Back：Back A 关表情面板，Back B 关快速回复，Back C 才出栈 PositionStack
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            advanceUntilVirtualCondition(2_000L) {
                composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, detailVm.uiState.value.page)
            assertTrue(detailVm.uiState.value.canPopPositionStack)
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            advanceVirtualAndMainLooper(200L)
            assertEquals(10, detailVm.uiState.value.page)

            // 3. FullEditor + EmojiPanel + LoginOverlay 返回优先级：LoginOverlay > EmojiPanel > FullEditor，且关闭编辑器重置表情态
            composeRule.mainClock.advanceTimeBy(11_000L)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            composeRule.runOnIdle { harness.hostVm.openLoginOverlay(LoginOverlayMode.Login) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("探针登录浮层").assertIsDisplayed()

            // Back 1: 仅关登录浮层
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            assertEquals(0, composeRule.onAllNodesWithText("探针登录浮层").fetchSemanticsNodes().size)
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            // Back 2: 仅关表情面板，编辑器保持打开
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
            assertTrue(harness.editorVm.uiState.value.isOpen)

            // Back 3: 关完整编辑器
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            assertFalse(harness.editorVm.uiState.value.isOpen)

            // 4. SMS 对话页 + EmojiPanel + 240dp 抽屉：抽屉 > EmojiPanel > 对话退出
            composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(net.jejer.hipda.ui.shell.ShellSidebarEntry.Sms)).performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithText("消息探针").performClick()
            advanceVirtualAndMainLooper(250L)
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertDoesNotExist()
            assertNotNull("关闭表情面板不得退出当前短消息对话", harness.hostVm.state.value.selectedSmsConversation)

            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            assertNull("再次返回才退出短消息对话", harness.hostVm.state.value.selectedSmsConversation)
        } finally {
            harness.close()
        }
    }

    @Test
    fun probe3c_rM7G4S2_smsImageOnlyBubbleHidesCopyMenu_textAndEmojiBubbleShowsCopy() {
        val smsRepo = object : SmsRepository {
            override val cooldownUntilMs = MutableStateFlow(0L)
            override fun getRemainingCooldownSeconds() = 0
            override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority) =
                MessageResult.Success(SmsListPage(emptyList(), probeMeta(), true))
            override suspend fun getConversationDetail(uid: String, priority: AccessPriority) = MessageResult.Success(
                SmsDetailPage(
                    myUid = "42",
                    myUsername = "HiProbeUser",
                    formhash = "fh",
                    messages = listOf(
                        SmsMessageItem(uid = "200", author = "图泡探针", avatarUrl = null, time = "10:00", contentHtml = "<img src=\"https://mock.local/only_image.png\" />", isNew = false, isMine = false),
                        SmsMessageItem(uid = "200", author = "图泡探针", avatarUrl = null, time = "10:01", contentHtml = "纯文本短消息", isNew = false, isMine = false),
                    ),
                    meta = probeMeta(),
                    hasContainer = true,
                ),
            )
            override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority) = SmsSendOutcome.Sent
            override suspend fun clearConversation(uid: String, priority: AccessPriority) = SmsClearOutcome.Cleared
        }
        val authRepo = ProbeAuthRepository(signedInAccount())
        val vm = SmsConversationViewModel(smsRepo, authRepo, createOfflineGateway())
        composeRule.setContent {
            val snackbarHost = remember { SnackbarHostState() }
            val controller = remember(snackbarHost) { SingleHostPaneSnackbarController(snackbarHost) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        SmsConversationScreen(
                            args = SmsConversationArgs("200", "图泡探针"),
                            showBack = true,
                            onBack = {},
                            onOpenUserProfile = { _, _ -> },
                            onOpenLogin = {},
                            contentPadding = PaddingValues(0.dp),
                            viewModel = vm,
                            emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                        )
                        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 纯图气泡：禁止出现 OnLongClick 语义动作（无「复制」菜单）
        val imgOnlyClickableNodes = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                hasAnyDescendant(hasText("10:00")),
        ).fetchSemanticsNodes()
        assertEquals("纯图气泡不应有长按复制动作", 0, imgOnlyClickableNodes.size)

        // 2. 纯文本气泡：长按正常弹出「复制」菜单
        composeRule.onNodeWithText("纯文本短消息").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("复制").assertIsDisplayed()
    }

    // =========================================================================
    // 探针 4：C1b#3 源码无硬编码 EmojiGroup.name 与边界守卫
    // =========================================================================

    @Test
    fun probe4_emojiPickerSourceDoesNotReadGroupName_andStringsEmojiHasNoLegacyLabels() {
        val moduleRoot = listOf(File("."), File("hipda")).first { File(it, "src/main/java").isDirectory }
        val pickerRaw = File(moduleRoot, "src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPickerPanel.kt").readText()
        val pickerCodeOnly = pickerRaw.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("EmojiPickerPanel 严禁读取硬编码 EmojiGroup.name", pickerCodeOnly.contains(".name"))
        assertTrue("EmojiPickerPanel 必须走 R.string.emoji_group_* 资源", pickerCodeOnly.contains("R.string.emoji_group_default"))
        val stringsEmoji = File(moduleRoot, "src/main/res/values/strings_emoji.xml").readText()
        assertTrue(stringsEmoji.contains("默认表情"))
        assertTrue(stringsEmoji.contains("酷猴表情"))
        assertTrue(stringsEmoji.contains("呆呆表情"))
    }

    // =========================================================================
    // 离线辅助与断言工具
    // =========================================================================

    private fun assertFrameEdgeStable(windowLabel: String, phaseLabel: String, baselineDp: Float, actualDp: Float) {
        val deltaDp = abs(actualDp - baselineDp)
        assertTrue(
            "[$windowLabel][$phaseLabel] 底缘发生位移！baseline=${baselineDp}dp, actual=${actualDp}dp, |Δbottom|=${deltaDp}dp (> 1dp)",
            deltaDp <= 1f,
        )
    }

    private fun stepFrameAndCheck(onStep: (String) -> Unit) {
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        onStep("postCompose")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        onStep("postLooper")
        composeRule.mainClock.advanceTimeByFrame()
        onStep("nextFrame")
    }

    private fun assertNoOverflowInsideWindow(windowBounds: Rect, panelBounds: Rect, toolBounds: Rect) {
        assertTrue("表情面板顶部(${panelBounds.top})不得溢出窗口顶部(${windowBounds.top})", panelBounds.top >= windowBounds.top - 1f)
        assertTrue("表情面板底部(${panelBounds.bottom})不得溢出窗口底部(${windowBounds.bottom})", panelBounds.bottom <= windowBounds.bottom + 1f)
        assertTrue("工具/输入控件顶部(${toolBounds.top})不得溢出窗口顶部(${windowBounds.top})", toolBounds.top >= windowBounds.top - 1f)
        assertTrue("工具/输入控件底部(${toolBounds.bottom})不得越入表情面板顶部(${panelBounds.top})", toolBounds.bottom <= panelBounds.top + 1f)
    }

    private fun assertAdaptiveGridCellSizesAndColumns(expectedCols: Int) {
        val density = composeRule.density
        val cellNodes = composeRule.onAllNodes(
            SemanticsMatcher("emojiGridCell") { node ->
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()
                cd != null && cd.startsWith("表情 ")
            },
        ).fetchSemanticsNodes()
        assertTrue("表情网格至少应渲染第一行格子", cellNodes.size >= expectedCols)
        cellNodes.forEach { n ->
            val wDp = with(density) { n.boundsInRoot.width.toDp().value }
            val hDp = with(density) { n.boundsInRoot.height.toDp().value }
            assertTrue("表情格宽 ${wDp}dp 必须 >= 48dp", wDp >= 47.9f)
            assertTrue("表情格高 ${hDp}dp 必须 >= 48dp", hDp >= 47.9f)
        }
        val firstRowY = cellNodes.first().boundsInRoot.top
        val firstRowCols = cellNodes.count { abs(it.boundsInRoot.top - firstRowY) <= 2f }
        assertEquals("自适应列数与面板可用宽度不匹配", expectedCols, firstRowCols)
    }

    private fun assertGlassBudgetWithinLimits(maxMarkers: Int, maxSources: Int, maxPasses: Int) {
        val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("同屏 HiGlassMarker=${markers.size} 超过 $maxMarkers", markers.size <= maxMarkers)
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val rootLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        val layerCount = countModifierName(rootLayout, "LayerBackdrop")
        val nestedCount = countNestedLayerBackdrops(rootLayout)
        val drawCount = countModifierName(rootLayout, "DrawBackdrop")
        assertTrue("LayerBackdrop=$layerCount 超过 $maxSources", layerCount <= maxSources)
        assertEquals("禁止嵌套 LayerBackdrop", 0, nestedCount)
        assertTrue("drawBackdrop=$drawCount 超过 $maxPasses", drawCount <= maxPasses)
    }

    private fun dispatchWindowInsets(hostView: View, imeDp: Int, navBarDp: Int, statusBarDp: Int = 0) {
        val density = composeRule.density
        val imePx = with(density) { imeDp.dp.roundToPx() }
        val navPx = with(density) { navBarDp.dp.roundToPx() }
        val statusPx = with(density) { statusBarDp.dp.roundToPx() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .build()
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(hostView, insets)
        }
    }

    private fun readEditableText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun readSelection(node: SemanticsNodeInteraction): TextRange =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange) ?: TextRange.Zero

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

    private fun advanceUntilVirtualCondition(timeoutMs: Long, stepMs: Long = 50L, predicate: () -> Boolean) {
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

    private fun waitUntilRealCondition(timeoutMs: Long, onTimeoutMessage: () -> String, predicate: () -> Boolean) {
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

    private inner class ProbeHarness(
        val detailRepo: ThreadDetailRepository = ProbeThreadDetailRepository(),
        customPostRepo: PostRepository? = null,
        private val customDb: HiDatabase? = null,
    ) {
        val db: HiDatabase = customDb ?: createInMemoryDb()
        val clockMs = AtomicLong(1_000_000L)
        val settingsRepo = ProbeSettingsRepository()
        val authRepo = ProbeAuthRepository(signedInAccount())
        val postRepo: PostRepository = customPostRepo ?: DbPostRepository(db.draftDao(), clockMs)
        val uploadRepo = ProbeUploadRepository()
        val gateway = createOfflineGateway()
        val favRepo = ProbeFavoriteRepository()
        val searchRepo = ProbeSearchRepository()
        val smsRepo = ProbeSmsRepository()
        val notifyRepo = ProbeNotifyRepository()
        val hostVm = NextAppHostViewModel(settingsRepo)
        val forumVm = ForumViewModel(ProbeThreadListRepository(), searchRepo, settingsRepo, authRepo, gateway)
        val requestBus = FullEditorRequestBus()
        val emojiRenderer = EmojiPanelRendererImpl()
        lateinit var editorVm: FullEditorViewModel
            private set

        private val createdDetailVms = LinkedHashMap<String, ThreadDetailViewModel>()

        private val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumVm as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(detailRepo, gateway, authRepo, favRepo, settingsRepo)
                        .also { createdDetailVms["1001"] = it } as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, authRepo, clockMs::get) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) ->
                    FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo, clockMs::get)
                        .also { editorVm = it } as T
                else -> throw IllegalArgumentException("Unexpected VM $modelClass")
            }
        }

        val owner: ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
        }

        fun detailVm(tid: String): ThreadDetailViewModel =
            ViewModelProvider(hostVm.viewModelStoreOwnerForThread(tid, vmFactory))["thread:$tid", ThreadDetailViewModel::class.java]

        private val imageViewerRenderer = ImageViewerRendererImpl(
            originalDownloader = object : OriginalImageDownloader {
                override fun download(url: String) = emptyFlow<OriginalDownloadState>()
            },
            mediaActions = object : ImageViewerMediaActions {
                override suspend fun saveImage(url: String, cachedFile: File?) = false
                override suspend fun shareImage(url: String, cachedFile: File?) = false
            },
        )

        private val fakeLoginOverlay = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) {
                    Text("探针登录浮层")
                }
            }
        }

        private val fakeAppSlotContext = FakeSlotApplication(
            base = ApplicationProvider.getApplicationContext(),
            fullEditorLauncher = requestBus,
            emojiPanelRenderer = emojiRenderer,
        )

        @Composable
        fun RenderAppRoot(observerInsideTheme: (@Composable () -> Unit)? = null) {
            val baseCtx = LocalContext.current
            val wrappedCtx = remember(baseCtx) {
                object : ContextWrapper(baseCtx) {
                    override fun getApplicationContext(): Context = fakeAppSlotContext
                }
            }
            CompositionLocalProvider(
                LocalContext provides wrappedCtx,
                LocalViewModelStoreOwner provides owner,
            ) {
                ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                    Box(Modifier.fillMaxSize()) {
                        NextAppRoot(
                            settingsFlow = settingsRepo.settings,
                            currentAccountFlow = authRepo.currentAccount,
                            smsUnreadCountFlow = MutableStateFlow(0),
                            notifyUnreadCountFlow = MutableStateFlow(0),
                            imageLoader = createOfflineImageLoader(),
                            loginOverlayRenderer = fakeLoginOverlay,
                            messagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway, Optional.of(emojiRenderer)),
                            searchMinePaneRenderer = SearchMinePaneRendererImpl(searchRepo, favRepo, ProbeBrowseHistoryDao(), authRepo, settingsRepo, gateway),
                            settingsProfilePaneRenderer = SettingsProfilePaneRendererImpl(
                                ApplicationProvider.getApplicationContext(),
                                settingsRepo,
                                ProbeNotificationScheduler(),
                                createOfflineImageLoader(),
                                authRepo,
                                ProbeBlacklistRepository(),
                                ProbeUserRepository(),
                            ),
                            imageViewerRenderer = imageViewerRenderer,
                            hostViewModel = hostVm,
                            forumViewModel = forumVm,
                            fullEditorRequestBus = requestBus,
                        )
                        if (observerInsideTheme != null) {
                            val currentSettings by settingsRepo.settingsFlow.collectAsStateWithLifecycle()
                            HiTheme(themeMode = when (currentSettings.themeMode) {
                                ThemeMode.LIGHT -> HiThemeMode.LIGHT
                                ThemeMode.DARK -> HiThemeMode.DARK
                                ThemeMode.SYSTEM -> HiThemeMode.SYSTEM
                            }) {
                                observerInsideTheme()
                            }
                        }
                    }
                }
            }
        }

        fun close() {
            if (customDb == null) db.close()
        }
    }

    private fun createInMemoryDb(): HiDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Application>(),
        HiDatabase::class.java,
    ).allowMainThreadQueries().build()

    private class FakeSlotApplication(
        base: Context,
        private val fullEditorLauncher: FullEditorLauncher,
        private val emojiPanelRenderer: QuickReplyEmojiPanelRenderer,
    ) : Application(), GeneratedComponentManagerHolder {
        private val entryPoint = object : GeneratedComponent, ComposeSlotsEntryPoint {
            override fun fullEditorLauncher(): Optional<FullEditorLauncher> = Optional.of(fullEditorLauncher)
            override fun emojiPanelRenderer(): Optional<QuickReplyEmojiPanelRenderer> = Optional.of(emojiPanelRenderer)
        }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun generatedComponent(): Any = entryPoint
        override fun componentManager() = throw UnsupportedOperationException()
    }

    private class DbPostRepository(
        private val draftDao: net.jejer.hipda.data.local.DraftDao,
        private val clockMs: AtomicLong,
    ) : PostRepository {
        private val _cooldownUntilMs = MutableStateFlow(0L)
        override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()
        override fun getRemainingCooldownSeconds() = 0
        override suspend fun fetchPrePost(request: PrePostRequest, priority: AccessPriority): PrePostResult =
            PrePostResult.Success(PrePostInfo("fh", "42", null, null, "", "", false, null, null, null, null, emptyList(), emptyList(), null, emptyMap(), probeMeta()))
        override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult {
            request.draftKey?.let { draftDao.delete(it.value) }
            return PostSubmitResult.Success("1001")
        }
        override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = draftDao.observe(key.value)
        override suspend fun saveDraft(key: DraftKey, subject: String, typeId: String, content: String, quotePid: String?, attachments: List<String>) {
            if (subject.isBlank() && content.isBlank() && attachments.isEmpty()) return
            draftDao.upsert(DraftEntity(key.value, subject, typeId, content, quotePid, attachments, clockMs.get()))
        }
        override suspend fun deleteDraft(key: DraftKey) = draftDao.delete(key.value)
    }

    private class ProbeUploadRepository : ImageUploadRepository {
        override fun uploadImage(input: ImageUploadInput, uid: String, hash: String, maxUploadBytes: Int?, priority: AccessPriority): Flow<ImageUploadProgress> =
            flowOf(ImageUploadProgress.Succeeded("700", 100L, true))
    }

    private class ProbeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val settingsFlow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = settingsFlow
        fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsFlow.update(transform)
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
    ) : ThreadDetailRepository {
        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val pageImage = imagePages[tid to page]
            val baseFloors = (1..15).map { idx ->
                val floorNo = (page - 1) * 15 + idx
                val pid = "p${page * 100 + idx}"
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

    private class ProbeSmsRepository : SmsRepository {
        override val cooldownUntilMs = MutableStateFlow(0L)
        override fun getRemainingCooldownSeconds() = 0
        override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority) =
            MessageResult.Success(SmsListPage(listOf(SmsConversationItem("100", "消息探针", null, "今天", "摘要", false)), probeMeta(), true))
        override suspend fun getConversationDetail(uid: String, priority: AccessPriority) =
            MessageResult.Success(SmsDetailPage("42", "HiProbeUser", "abc", listOf(SmsMessageItem("100", "消息探针", null, "10:00", "历史消息", false, false)), probeMeta(), true))
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

    private fun createOfflineImageLoader(): ImageLoader {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                SuccessResult(ColorImage(AndroidColor.LTGRAY, 320, 240), chain.request, DataSource.MEMORY_CACHE)
            })
        }.build()
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
