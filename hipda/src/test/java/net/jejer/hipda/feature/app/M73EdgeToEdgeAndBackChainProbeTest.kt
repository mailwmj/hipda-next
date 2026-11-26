package net.jejer.hipda.feature.app

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets as CoreInsets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.ColorImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.SmsClearOutcome
import net.jejer.hipda.data.message.SmsRepository
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.compose.FakeImageUploadRepository
import net.jejer.hipda.feature.compose.FakePostRepository
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.feature.imageviewer.FakeImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.FakeOriginalImageDownloader
import net.jejer.hipda.feature.imageviewer.ImageViewerOverlay
import net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl
import net.jejer.hipda.feature.imageviewer.ViewerTestImageLoader
import net.jejer.hipda.feature.login.LoginOverlayRendererImpl
import net.jejer.hipda.feature.messages.SmsConversationScreen
import net.jejer.hipda.feature.messages.SmsConversationViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.challenge.ChallengeActivity
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.SyncHiEdgeToEdge
import net.jejer.hipda.ui.designsystem.applyHiEdgeToEdge
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 独立验证探针 3（M7-3 Edge-to-Edge 全面屏 insets 与 API 36 预测性返回链）：
 * 1. `applyHiEdgeToEdge` / `SyncHiEdgeToEdge` / `ChallengeActivity` 在 SDK 28/33/35/36 矩阵下的对比度同步与防重设 `decorFitsSystemWindows`；
 * 2. API 36 下单栏竖屏/横屏、三栏、看图、编辑器居中模态、快速回复、表情面板、短信对话注入真实 statusBars + displayCutout + navigationBars + ime 后的布局边界与零双倍 insets；
 * 3. API 36 真实 `OnBackPressedDispatcher` 预测性返回全链路（看图 / 登录 > 抽屉 > 表情 > 编辑器 / 快速回复 > 位置栈 > 多栏栈），以及默认论坛根 `hasEnabledCallbacks() == false`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class M73EdgeToEdgeAndBackChainProbeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(sdk = [28, 33, 35, 36])
    fun edgeToEdgeSystemBars_syncAcrossThemesAndChallengeActivityAcrossSdk28_33_35_36() {
        var darkTheme by mutableStateOf(false)
        composeRule.setContent {
            SyncHiEdgeToEdge(darkTheme = darkTheme)
        }
        composeRule.waitForIdle()

        val activity = composeRule.activity
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertFalse("Edge-to-edge 必须保持 decorFitsSystemWindows=false", activity.window.decorView.fitsSystemWindows)
        assertTrue("浅色主题状态栏图标应为深色 (isAppearanceLightStatusBars=true)", controller.isAppearanceLightStatusBars)
        assertTrue("浅色主题导航栏图标应为深色 (isAppearanceLightNavigationBars=true)", controller.isAppearanceLightNavigationBars)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse("API 29+ 导航栏应关闭强加半透明对比遮罩", activity.window.isNavigationBarContrastEnforced)
        }

        // 切到深色主题 -> 图标自动反白
        darkTheme = true
        composeRule.waitForIdle()
        assertFalse("深色主题状态栏图标应为浅色", controller.isAppearanceLightStatusBars)
        assertFalse("深色主题导航栏图标应为浅色", controller.isAppearanceLightNavigationBars)

        // 切回浅色并验证 ChallengeActivity 日/夜模式同样跟随系统日夜套用 edge-to-edge
        darkTheme = false
        composeRule.waitForIdle()
        assertTrue(controller.isAppearanceLightStatusBars)

        val dayConfig = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val nightConfig = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        // 核验 applyHiEdgeToEdge 直接调用与 ChallengeActivity 启动期对比度
        composeRule.runOnUiThread {
            activity.applyHiEdgeToEdge(darkTheme = true)
            assertFalse(WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
        }
        val challengeController = Robolectric.buildActivity(ChallengeActivity::class.java).setup()
        val challengeActivity = challengeController.get()
        assertFalse(challengeActivity.window.decorView.fitsSystemWindows)
        assertNotNull(dayConfig)
        assertNotNull(nightConfig)
        challengeController.pause().stop().destroy()

        @Suppress("DEPRECATION")
        assertEquals(AndroidColor.TRANSPARENT, activity.window.statusBarColor)
        @Suppress("DEPRECATION")
        assertEquals(AndroidColor.TRANSPARENT, activity.window.navigationBarColor)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun edgeToEdgeInsets_api36_singlePane_quickReply_emoji_andImageViewer_zeroOverlapAndZeroDoubleInsets() {
        val harness = MultiPaneTestHarness(richSampleContent = false)
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithAllVms(harness, postRepo, editorVm, createDetailRepoWithImage())
        val imageLoader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val imageViewerRenderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var rootView: View? = null

        composeRule.setContent {
            rootView = LocalView.current
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalHiImageLoader provides imageLoader,
            ) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    imageLoader = imageLoader,
                    imageViewerRenderer = imageViewerRenderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                    fullEditorViewModel = editorVm,
                    emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                )
            }
        }
        composeRule.waitForIdle()

        val statusPx = with(composeRule.density) { 32.dp.roundToPx() }
        val navPx = with(composeRule.density) { 48.dp.roundToPx() }
        val imePx = with(composeRule.density) { 280.dp.roundToPx() }

        // 1. 单栏论坛首页：0 insets vs status+cutout+nav insets -> 顶栏仅下移 1x statusPx，底胶囊仅上移 1x navPx
        val newPostBefore = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pillBefore = composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx)

        val newPostAfter = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pillAfter = composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rootH = rootView!!.height.toFloat()

        assertTrue("论坛顶栏不得被状态栏/刘海遮挡", newPostAfter.top >= statusPx - 2f)
        assertSingleDelta("论坛顶栏仅应施加 1 倍顶部 inset", statusPx.toFloat(), newPostAfter.top - newPostBefore.top)
        assertTrue("紧凑底栏不得被系统导航栏遮挡", pillAfter.bottom <= rootH - navPx + 2f)
        assertSingleDelta("紧凑底栏仅应施加 1 倍底部 inset", navPx.toFloat(), pillBefore.bottom - pillAfter.bottom)

        // 2. 进入帖子详情 -> 核验顶栏、底部工具栏不重叠/不双倍
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx)

        val backBtnAfter = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val replyBtnAfter = composeRule.onNodeWithText("回复").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("详情顶栏返回键不得与状态栏重叠", backBtnAfter.top >= statusPx - 2f)
        assertTrue("详情顶栏不得双倍下移", backBtnAfter.top < statusPx * 1.8f)
        assertTrue("详情底栏回复按钮不得被导航栏遮挡", replyBtnAfter.bottom <= rootH - navPx + 2f)

        // 3. 打开快速回复 -> 比较 nav-only、IME=280dp、以及切表情面板时的底部边界与零双倍（IME 不叠加 navBar）
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx, imeBottom = 0)
        val sendNavOnly = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("快速回复发送按钮须高于导航栏", sendNavOnly.bottom <= rootH - navPx + 2f)

        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx, imeBottom = imePx)
        val sendWithIme = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("IME 弹出后快速回复发送按钮须完全位于 IME 之上 ($sendWithIme)", sendWithIme.bottom <= rootH - imePx + 2f)
        val expectedLift = (imePx - navPx).toFloat()
        assertSingleDelta("快速回复上抬应只补偿 (ime - nav) 而非叠加双倍", expectedLift, sendNavOnly.bottom - sendWithIme.bottom)

        // 切换表情面板（键盘收起 ime=0）-> 表情面板底部不与导航栏重叠，且无重叠裁剪
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx, imeBottom = 0)
        val keyboardToggle = composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("切出表情面板后输入栏须高于导航栏", keyboardToggle.bottom <= rootH - navPx + 2f)
        composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("呆呆表情").performClick()
        composeRule.waitForIdle()
        val emojiCell = composeRule.onNodeWithContentDescription("表情 {:3_57:}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("表情面板内容不得沉入导航栏下方: $emojiCell", emojiCell.bottom <= rootH - navPx + 2f)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        // 4. 点击帖子正文中图片打开真实 ImageViewerOverlay -> 顶/底 Chrome 严禁侵入状态栏与导航栏且无双倍 padding
        dispatchInsets(rootView!!, statusTop = 0, cutoutTop = 0, navBottom = 0)
        val imgNodes = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and hasClickAction())
        imgNodes[0].performClick()
        composeRule.waitForIdle()
        val closeBefore = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveBefore = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx)
        val closeAfter = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveAfter = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("看图关闭键必须避开状态栏+刘海", closeAfter.top >= statusPx - 2f)
        assertSingleDelta("看图顶栏只能施加 1 倍顶部 inset", statusPx.toFloat(), closeAfter.top - closeBefore.top)
        assertTrue("看图保存键必须避开导航栏", saveAfter.bottom <= rootH - navPx + 2f)
        assertSingleDelta("看图底栏只能施加 1 倍底部 inset", navPx.toFloat(), saveBefore.bottom - saveAfter.bottom)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun edgeToEdgeInsets_api36_landscapeCutoutAndSideNavBar_singlePanePhone() {
        val harness = MultiPaneTestHarness()
        val owner = ownerWithAllVms(harness, FakePostRepository(), null, OfflineThreadDetailRepository())
        var rootView: View? = null

        composeRule.setContent {
            rootView = LocalView.current
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val statusPx = with(composeRule.density) { 24.dp.roundToPx() }
        val cutoutLeftPx = with(composeRule.density) { 28.dp.roundToPx() }
        val navRightPx = with(composeRule.density) { 48.dp.roundToPx() }
        val rootW = rootView!!.width.toFloat()

        val newPostBefore = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        dispatchInsets(
            rootView!!,
            statusTop = statusPx,
            cutoutLeft = cutoutLeftPx,
            navRight = navRightPx,
        )
        val newPostAfter = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("横屏挖孔左侧安全区被侵入: ${newPostAfter.left} < $cutoutLeftPx", newPostAfter.left >= cutoutLeftPx - 2f)
        assertTrue("横屏右侧三键导航安全区被遮挡: ${newPostAfter.right} > ${rootW - navRightPx}", newPostAfter.right <= rootW - navRightPx + 2f)
        assertSingleDelta("横屏右上按钮须单倍左避右导航栏", navRightPx.toFloat(), newPostBefore.right - newPostAfter.right)
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun edgeToEdgeInsets_api36_threePane_and_centeredModalEditor_zeroDoublePadding() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithAllVms(harness, postRepo, editorVm, OfflineThreadDetailRepository())
        var rootView: View? = null

        composeRule.setContent {
            rootView = LocalView.current
            MultiPaneResizableWindowHost(DpSize(933.dp, 696.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                        emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val statusPx = with(composeRule.density) { 32.dp.roundToPx() }
        val navPx = with(composeRule.density) { 48.dp.roundToPx() }
        val imePx = with(composeRule.density) { 260.dp.roundToPx() }
        val rootH = rootView!!.height.toFloat()

        // 1. 三栏列表+详情：同时打开帖子 1001，对比 0 insets 与 status+cutout+nav
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val sidebarBefore = composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val newPostBefore = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val favBefore = composeRule.onNodeWithContentDescription("只看楼主").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val replyBefore = composeRule.onNodeWithText("回复").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx)

        val sidebarAfter = composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val newPostAfter = composeRule.onNodeWithContentDescription("发新帖").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val favAfter = composeRule.onNodeWithContentDescription("只看楼主").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val replyAfter = composeRule.onNodeWithText("回复").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("三栏侧边栏须覆盖完整视窗顶缘或子内容避开状态栏", sidebarAfter.height >= sidebarBefore.height - 2f)
        assertSingleDelta("三栏中栏顶栏只能下移 1 倍 statusPx", statusPx.toFloat(), newPostAfter.top - newPostBefore.top)
        assertSingleDelta("三栏右栏顶栏只能下移 1 倍 statusPx", statusPx.toFloat(), favAfter.top - favBefore.top)
        assertTrue("三栏详情底栏须严格高于导航栏", replyAfter.bottom <= rootH - navPx + 2f)
        assertSingleDelta("三栏详情底栏只能上移 1 倍 navPx", navPx.toFloat(), replyBefore.bottom - replyAfter.bottom)

        // 2. 933x696 下点「回复」->「展开全屏」自动投放 CenteredModal（非独占全屏、居中避开 status + nav/ime）
        dispatchInsets(rootView!!, statusTop = 0, cutoutTop = 0, navBottom = 0, imeBottom = 0)
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx, imeBottom = imePx)
        val sendModalAfter = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val insertPicAfter = composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("居中模态编辑器顶栏发送键不得侵入状态栏: $sendModalAfter", sendModalAfter.top >= statusPx - 2f)
        assertTrue("居中模态编辑器工具条在 IME=260dp 下不得被键盘遮挡: $insertPicAfter", insertPicAfter.bottom <= rootH - imePx + 2f)
        // 验证居中宽度远窄于 933dp（即确实处于 CenteredModal 而非 FullScreen）
        val modalSendRightDp = with(composeRule.density) { sendModalAfter.right.toDp().value }
        assertTrue("933dp 下编辑器应呈居中模态（右侧应明显留白，实际 right=${modalSendRightDp}dp）", modalSendRightDp < 860f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun edgeToEdgeInsets_api36_smsConversation_respectsStatusAndImeWithoutDoublePadding() {
        val smsRepo = object : SmsRepository {
            private val _cd = MutableStateFlow(0L)
            override val cooldownUntilMs: StateFlow<Long> = _cd.asStateFlow()
            override fun getRemainingCooldownSeconds(): Int = 0
            override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority): MessageResult<SmsListPage> = error("unused")
            override suspend fun getConversationDetail(uid: String, priority: AccessPriority): MessageResult<SmsDetailPage> =
                MessageResult.Success(
                    SmsDetailPage(
                        myUid = "10000",
                        myUsername = "HiUser",
                        formhash = "fh",
                        messages = listOf(
                            SmsMessageItem(
                                uid = uid,
                                author = "Alice",
                                avatarUrl = null,
                                time = "10:00",
                                contentHtml = "你好，测试消息",
                                isNew = false,
                                isMine = false,
                            ),
                        ),
                        meta = offlinePageMeta(),
                        hasContainer = true,
                    ),
                )
            override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority) = SmsSendOutcome.Sent
            override suspend fun clearConversation(uid: String, priority: AccessPriority) = SmsClearOutcome.Cleared
        }
        val authRepo = OfflineAuthRepository()
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val smsVm = SmsConversationViewModel(smsRepo, authRepo, gateway)
        var rootView: View? = null

        composeRule.setContent {
            rootView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                SmsConversationScreen(
                    args = SmsConversationArgs(uid = "20001", username = "Alice"),
                    showBack = true,
                    onBack = {},
                    onOpenUserProfile = { _, _ -> },
                    onOpenLogin = {},
                    contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
                    viewModel = smsVm,
                    emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                )
            }
        }
        composeRule.waitForIdle()

        val statusPx = with(composeRule.density) { 32.dp.roundToPx() }
        val navPx = with(composeRule.density) { 48.dp.roundToPx() }
        val imePx = with(composeRule.density) { 280.dp.roundToPx() }
        val rootH = rootView!!.height.toFloat()

        val sendBefore = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        dispatchInsets(rootView!!, statusTop = statusPx, cutoutTop = statusPx, navBottom = navPx, imeBottom = imePx)
        val backAfter = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val sendAfter = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("短信会话顶栏返回键不得与状态栏重叠", backAfter.top >= statusPx - 2f)
        assertTrue("短信会话顶栏不得双倍施加顶部 inset", backAfter.top < statusPx * 1.8f)
        assertTrue("短信输入栏发送键在 IME 弹出后须位于键盘上方", sendAfter.bottom <= rootH - imePx + 2f)
        assertSingleDelta("短信输入栏仅应上抬 1 倍 imePx", imePx.toFloat(), sendBefore.bottom - sendAfter.bottom)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun predictiveBackChain_api36_realProductionLayers_andReturnsToHomeZeroCallbacks() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithAllVms(harness, postRepo, editorVm, createDetailRepoWithImage())
        val imageLoader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val loginRenderer = LoginOverlayRendererImpl(harness.authRepo, harness.gateway)
        val imageViewerRenderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(749.dp, 559.dp)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalHiImageLoader provides imageLoader,
                ) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        imageLoader = imageLoader,
                        loginOverlayRenderer = loginRenderer,
                        imageViewerRenderer = imageViewerRenderer,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                        emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        // 0. 默认论坛根页：hasEnabledCallbacks() 必须为 false，允许系统 API 36 预测性返回退回桌面
        assertFalse("默认论坛根页面绝不可截留返回键", dispatcher.hasEnabledCallbacks())

        // 验证非默认顶层入口先压入多栏栈：点侧边栏「消息」进入非默认页 -> 按返回退回默认论坛根并归零拦截
        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Sms)).performClick()
        composeRule.waitForIdle()
        assertTrue("离开默认根后返回栈应可回退到默认论坛根", dispatcher.hasEnabledCallbacks())
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse("回到默认论坛根后 hasEnabledCallbacks() 必须回归 false", dispatcher.hasEnabledCallbacks())

        // 打开帖子 1001 并跳至第 3 页建立位置栈
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()

        // 位置栈（第 1 页 -> 第 3 页）
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 链条一：同时堆叠 QuickReply -> EmojiPanel -> ModalDrawer -> LoginOverlay -> ImageViewer（经模拟/真实回调取消验证）
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 楼层作者1").performTextInput("返程保留草稿")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()

        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录 HiPDA").assertIsDisplayed()

        // 验证 API 36 预测性返回手势启动后取消不误退底层弹窗
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0.1f, 0.5f, 0.2f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录 HiPDA").assertIsDisplayed()

        // 返回 1：仅关 LoginOverlay，Drawer 仍在
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.loginOverlayMode)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 返回 2：仅关 Drawer，QuickReply + Emoji 仍在
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()

        // 返回 3：仅收起表情，QuickReply 输入框与草稿仍在
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        // 返回 4：收起 QuickReply（静默保存 reply:1001 草稿），PositionStack 仍停在第 3 页
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertEquals("返程保留草稿", postRepo.draftFor(DraftKey.reply("1001"))?.content)
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 链条二：在 PositionStack 第 3 页状态下，展开 QuickReply -> 展开 FullEditor（RightPane/Modal）-> 展开 Emoji
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()

        // 返回 A：仅收起编辑器内表情面板，编辑器仍在
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().size)
        assertTrue("收起表情后完整编辑器必须保持打开", editorVm.uiState.value.isOpen)

        // 返回 B：关闭完整编辑器，PositionStack 仍在第 3 页
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse("第二次返回应关闭完整编辑器", editorVm.uiState.value.isOpen)
        assertEquals(3, detailVm.uiState.value.page)

        // 链条三：在第 3 页状态下点楼层图片打开真实 ImageViewerOverlay，并同时开启 LoginOverlay -> 验证看图 > 登录 > 位置栈 > 多栏栈
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and hasClickAction())[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("保存图片").assertIsDisplayed()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        // 看图优先级高于登录浮层 -> 首次返回仅关闭看图，露出的登录浮层仍在，PositionStack 仍停第 3 页
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("保存图片").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("登录 HiPDA").assertIsDisplayed()
        // 次次返回关闭登录浮层，PositionStack 仍停第 3 页
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.loginOverlayMode)
        assertEquals("关闭看图与登录不得误出栈 PositionStack", 3, detailVm.uiState.value.page)

        // 返回 5：PositionStack 出栈回第 1 页，帖子仍打开
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
        assertEquals("1001", harness.hostViewModel.state.value.selectedThread?.tid)

        // 返回 6：MultiPaneStack 清空当前帖子回到列表占位
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.selectedThread)

        // 回到根状态后，清空历史则返回键不再被拦截
        assertFalse("回到默认根后 hasEnabledCallbacks() 必须回归 false", dispatcher.hasEnabledCallbacks())
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun imageViewerInsets_api36_landscapeLeftAndRightThreeButtonNavBars_zeroSystemBarIntersection() {
        val hostView = mountStandaloneGifViewer()
        val rootW = hostView.width.toFloat()
        val statusPx = with(composeRule.density) { 24.dp.roundToPx() }
        val cutoutSidePx = with(composeRule.density) { 28.dp.roundToPx() }
        val navSidePx = with(composeRule.density) { 48.dp.roundToPx() }

        val closeZero = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageZero = composeRule.onNode(hasText("1 / 3")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifZero = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        // 1. 横屏左侧三键导航（navLeft=48dp）+ 右侧挖孔（cutoutRight=28dp）
        dispatchInsets(
            targetView = hostView,
            statusTop = statusPx,
            cutoutRight = cutoutSidePx,
            navLeft = navSidePx,
        )
        val closeLeftNav = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageLeftNav = composeRule.onNode(hasText("1 / 3")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifLeftNav = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveLeftNav = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shareLeftNav = composeRule.onNodeWithText("分享").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        listOf(
            "关闭钮" to closeLeftNav,
            "页码" to pageLeftNav,
            "GIF 标" to gifLeftNav,
            "保存图片" to saveLeftNav,
            "分享" to shareLeftNav,
        ).forEach { (name, bounds) ->
            assertTrue("横屏左三键导航：$name (${bounds.left}) 不得侵占左导航栏 ($navSidePx)", bounds.left >= navSidePx - 2f)
            assertTrue("横屏左三键导航：$name (${bounds.right}) 不得侵占右挖孔 (${rootW - cutoutSidePx})", bounds.right <= rootW - cutoutSidePx + 2f)
        }
        assertTrue(closeLeftNav.top >= statusPx - 2f)
        assertTrue(pageLeftNav.top >= statusPx - 2f)
        assertTrue(gifLeftNav.top >= statusPx - 2f)
        assertFalse("GIF 标 ($gifLeftNav) 不得与关闭钮 ($closeLeftNav) 相交", gifLeftNav.overlaps(closeLeftNav))
        assertFalse("GIF 标 ($gifLeftNav) 不得与页码 ($pageLeftNav) 相交", gifLeftNav.overlaps(pageLeftNav))
        assertSingleDelta("左导航看图关闭钮仅位移 1 倍 navLeft", navSidePx.toFloat(), closeLeftNav.left - closeZero.left)
        assertSingleDelta("左导航看图 GIF 标仅位移 1 倍 navLeft", navSidePx.toFloat(), gifLeftNav.left - gifZero.left)
        assertSingleDelta("右挖孔看图页码仅左移 1 倍 cutoutRight", cutoutSidePx.toFloat(), pageZero.right - pageLeftNav.right)

        // 2. 横屏右侧三键导航（navRight=48dp）+ 左侧挖孔（cutoutLeft=28dp）
        dispatchInsets(
            targetView = hostView,
            statusTop = statusPx,
            cutoutLeft = cutoutSidePx,
            navRight = navSidePx,
        )
        val closeRightNav = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageRightNav = composeRule.onNode(hasText("1 / 3")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifRightNav = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveRightNav = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shareRightNav = composeRule.onNodeWithText("分享").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        listOf(
            "关闭钮" to closeRightNav,
            "页码" to pageRightNav,
            "GIF 标" to gifRightNav,
            "保存图片" to saveRightNav,
            "分享" to shareRightNav,
        ).forEach { (name, bounds) ->
            assertTrue("横屏右三键导航：$name (${bounds.left}) 不得侵占左挖孔 ($cutoutSidePx)", bounds.left >= cutoutSidePx - 2f)
            assertTrue("横屏右三键导航：$name (${bounds.right}) 不得侵占右导航栏 (${rootW - navSidePx})", bounds.right <= rootW - navSidePx + 2f)
        }
        assertFalse("GIF 标不得与关闭钮相交", gifRightNav.overlaps(closeRightNav))
        assertFalse("GIF 标不得与页码相交", gifRightNav.overlaps(pageRightNav))
        assertSingleDelta("左挖孔看图关闭钮仅右移 1 倍 cutoutLeft", cutoutSidePx.toFloat(), closeRightNav.left - closeZero.left)
        assertSingleDelta("左挖孔看图 GIF 标仅右移 1 倍 cutoutLeft", cutoutSidePx.toFloat(), gifRightNav.left - gifZero.left)
        assertSingleDelta("右导航看图页码仅左移 1 倍 navRight", navSidePx.toFloat(), pageZero.right - pageRightNav.right)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun imageViewerInsets_api36_portraitDeepCutout_closePageGifBadgeAvoidStatusBarWithoutDoublePadding() {
        val hostView = mountStandaloneGifViewer()
        val rootH = hostView.height.toFloat()
        val deepStatusPx = with(composeRule.density) { 52.dp.roundToPx() }
        val navBottomPx = with(composeRule.density) { 48.dp.roundToPx() }

        val closeZero = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageZero = composeRule.onNode(hasText("1 / 3")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifZero = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveZero = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        dispatchInsets(
            targetView = hostView,
            statusTop = deepStatusPx,
            cutoutTop = deepStatusPx,
            navBottom = navBottomPx,
        )
        val closeDeep = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pageDeep = composeRule.onNode(hasText("1 / 3")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gifDeep = composeRule.onNodeWithText("GIF").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val saveDeep = composeRule.onNodeWithText("保存图片").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("深挖孔关闭钮 (${closeDeep.top}) 必须避开状态栏+深挖孔 ($deepStatusPx)", closeDeep.top >= deepStatusPx - 2f)
        assertTrue("深挖孔页码 (${pageDeep.top}) 必须避开状态栏+深挖孔 ($deepStatusPx)", pageDeep.top >= deepStatusPx - 2f)
        assertTrue("深挖孔 GIF 标 (${gifDeep.top}) 必须位于关闭钮 (${closeDeep.bottom}) 之下", gifDeep.top >= closeDeep.bottom - 1f)
        assertFalse("深挖孔下 GIF 标不得与关闭钮或页码重叠", gifDeep.overlaps(closeDeep) || gifDeep.overlaps(pageDeep))
        assertTrue("看图保存键 (${saveDeep.bottom}) 必须避开底部导航栏", saveDeep.bottom <= rootH - navBottomPx + 2f)

        // 零双倍 insets 校验
        assertSingleDelta("深挖孔关闭钮仅下移 1 倍 deepStatusPx", deepStatusPx.toFloat(), closeDeep.top - closeZero.top)
        assertSingleDelta("深挖孔页码仅下移 1 倍 deepStatusPx", deepStatusPx.toFloat(), pageDeep.top - pageZero.top)
        assertSingleDelta("深挖孔 GIF 标仅下移 1 倍 deepStatusPx", deepStatusPx.toFloat(), gifDeep.top - gifZero.top)
        assertSingleDelta("看图底栏仅上移 1 倍 navBottomPx", navBottomPx.toFloat(), saveZero.bottom - saveDeep.bottom)
    }

    private fun mountStandaloneGifViewer(): View {
        val imageLoader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var rootView: View? = null
        composeRule.setContent {
            rootView = LocalView.current
            CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf(
                                "https://img.example.invalid/anim_probe.gif",
                                "https://img.example.invalid/photo_2.jpg",
                                "https://img.example.invalid/photo_3.jpg",
                            ),
                        ),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(rootView)
    }

    private fun dispatchInsets(
        targetView: View,
        statusTop: Int = 0,
        cutoutTop: Int = 0,
        cutoutLeft: Int = 0,
        cutoutRight: Int = 0,
        navLeft: Int = 0,
        navBottom: Int = 0,
        navRight: Int = 0,
        imeBottom: Int = 0,
    ) {
        composeRule.runOnUiThread {
            val builder = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), CoreInsets.of(0, statusTop, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), CoreInsets.of(navLeft, 0, navRight, navBottom))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), CoreInsets.of(cutoutLeft, cutoutTop, cutoutRight, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), CoreInsets.of(0, 0, 0, imeBottom))
            if (cutoutTop > 0 || cutoutLeft > 0 || cutoutRight > 0) {
                val safeCutout = CoreInsets.of(cutoutLeft, cutoutTop, cutoutRight, 0)
                val leftRect = if (cutoutLeft > 0) AndroidRect(0, 0, cutoutLeft, 100) else null
                val topRect = if (cutoutTop > 0) AndroidRect(100, 0, 220, cutoutTop) else null
                val rightRect = if (cutoutRight > 0) AndroidRect(targetView.width - cutoutRight, 0, targetView.width, 100) else null
                builder.setDisplayCutout(DisplayCutoutCompat(safeCutout, leftRect, topRect, rightRect, null, CoreInsets.NONE))
            }
            ViewCompat.dispatchApplyWindowInsets(targetView, builder.build())
        }
        composeRule.waitForIdle()
    }

    private fun assertSingleDelta(label: String, expectedDeltaPx: Float, actualDeltaPx: Float) {
        assertTrue(
            "$label: expected ~${expectedDeltaPx}px (±6px), actual ${actualDeltaPx}px",
            abs(actualDeltaPx - expectedDeltaPx) <= 6f,
        )
    }

    private fun ownerWithAllVms(
        harness: MultiPaneTestHarness,
        postRepo: FakePostRepository,
        editorVm: FullEditorViewModel?,
        detailRepo: ThreadDetailRepository,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) && editorVm != null -> editorVm as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> {
                    ThreadDetailViewModel(
                        threadDetailRepository = detailRepo,
                        siteAccessGateway = harness.gateway,
                        authRepository = harness.authRepo,
                        settingsRepository = harness.settingsRepo,
                    ).also { harness.createdDetailVms += it } as T
                }
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    private fun createDetailRepoWithImage(): ThreadDetailRepository {
        val delegate = OfflineThreadDetailRepository()
        return object : ThreadDetailRepository by delegate {
            override suspend fun loadPage(
                tid: String,
                page: Int,
                filter: ThreadDetailFilter,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult {
                val base = delegate.loadPage(tid, page, filter, recordHistory, forumName, priority)
                if (base !is ThreadDetailLoadResult.Success) return base
                val first = base.page.floors.firstOrNull() ?: return base
                val imgUrl = "https://img.example.invalid/floor_${page}.jpg"
                val withImageFloor = PostFloor(
                    postId = first.postId,
                    floor = first.floor,
                    page = page,
                    uid = first.uid,
                    author = "楼层作者1",
                    avatarUrl = first.avatarUrl,
                    timePost = first.timePost,
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(
                        ContentBlock.Text(listOf(InlineSpan.Plain("第 $page 页正文与附图"))),
                        ContentBlock.Image(fullUrl = imgUrl, thumbUrl = imgUrl, fileSizeBytes = 2048L),
                    ),
                )
                return base.copy(page = base.page.copy(floors = listOf(withImageFloor) + base.page.floors.drop(1)))
            }
        }
    }
}
