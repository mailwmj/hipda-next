package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
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
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.auditGlassModifiers
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.FakeImageUploadRepository
import net.jejer.hipda.feature.compose.FakePostRepository
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.compose.RecordingPaneSnackbarController
import net.jejer.hipda.feature.compose.defaultSamplePrePostInfo
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * E6b 端到端离线集成测试（0 访问真实站点）：
 * 1. 删除自己楼层全量 `MockWebServer` + 真实 `DefaultPostRepository` 三路径：
 *    - 取消（点「取消」与按 Back 均 0 网络请求、不误弹 `PositionStack`）；
 *    - 成功（普通楼层移除保持阅读位置、清理 `edit:<pid>` 草稿、严禁弹“撤销”Snackbar；首楼整帖删除关闭详情并刷新列表）；
 *    - 失败（服务端错误、30s 冷却、网络错误 -> 保留楼层、原地提示、允许重试成功）；
 * 2. 真实 `NextAppRoot` 全链路（`411dp` / `749dp` / `1280dp`）：
 *    - 长按自己楼层点「编辑」复用 E6 §4.2 多栏投放、还原原文+撤销、提交原地刷新楼层不跳位、返回优先级与玻璃预算 `≤ 3`；
 *    - 749dp 三栏删除首楼整帖删除 -> 清空右栏回占位并刷新中栏列表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EditAndDeletePostIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var virtualClockMs: AtomicLong
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext<Application>())
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private data class RealPostStack(
        val postRepo: DefaultPostRepository,
        val authRepo: DefaultAuthRepository,
        val draftDao: InMemoryDraftDao,
    )

    private fun createRealPostStack(): RealPostStack {
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credentialsStore = PostTestCredentialsStore()
        val settingsRepo = PostTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/'), tailEnabled = false),
        )
        runBlocking {
            val acc = SavedAccount("1001", "本人账号", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
            credentialsStore.upsert(acc)
            credentialsStore.setActive(acc.uid)
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) }
        val primaryGw = SiteAccessGateway(primaryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val recoveryGw = SiteAccessGateway(recoveryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val authRepo = DefaultAuthRepository(credentialsStore, cookieStore, settingsRepo, primaryGw, recoveryGw, { virtualClockMs.get() }, scope)
        val forumHttp = ForumHttpClient(primaryGw, settingsRepo, authRepo)
        val draftDao = InMemoryDraftDao()
        val postRepo = DefaultPostRepository(forumHttp, authRepo, settingsRepo, draftDao, { virtualClockMs.get() }, scope)
        return RealPostStack(postRepo, authRepo, draftDao)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun deleteOwnFloor_mockWebServer_cancelSuccessPreserveScrollFailureRetryAndFirstFloorDeleteThread() {
        val deleteAttempts = AtomicInteger(0)
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        val postRequests = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("post.php?action=edit") && request.method == "GET" ->
                        gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "del_fh_01", uid = "1001", username = "本人账号", isDeletable = true))

                    target.contains("post.php?action=edit") && target.contains("editsubmit=yes") && request.method == "POST" -> {
                        postRequests += "$target | ${request.body!!.utf8()}"
                        when {
                            target.contains("pid=5001") -> {
                                // 首楼删除致整帖删除 -> 302 forumdisplay.php
                                MockResponse.Builder().code(302).addHeader("Location", "/forum/forumdisplay.php?fid=2").build()
                            }
                            deleteAttempts.incrementAndGet() == 1 -> {
                                // 第一次删普通楼层返回服务端错误
                                deleteStarted.countDown()
                                releaseDelete.await(5, TimeUnit.SECONDS)
                                gbkHtmlResponse(PostTestFixtures.loggedInHtml("1001", "本人账号", "<div class=\"alert_error\">无权删除已锁定楼层</div>"))
                            }
                            deleteAttempts.get() == 2 -> {
                                // 第二次模拟 500 网络异常
                                MockResponse.Builder().code(500).body("Internal Server Error").build()
                            }
                            else -> {
                                // 第三次删普通楼层成功 -> 302 viewthread.php
                                MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=77001&page=1").build()
                            }
                        }
                    }

                    target.contains("viewthread.php") || target.contains("forumdisplay.php") ->
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml("1001", "本人账号", "<div id=\"postlist\"></div>"))

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val realStack = createRealPostStack()
        // 预先存入待删楼层 5007 的 edit:5007 草稿，验证删除成功后一并清理
        val floor7DraftKey = DraftKey.edit("5007")
        runBlocking { realStack.postRepo.saveDraft(floor7DraftKey, content = "待随楼层删除清理的草稿") }

        val detailRepo = EditableFloorsDetailRepository(ownUid = "1001", floorCount = 15)
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), realStack.authRepo, postRepository = realStack.postRepo)
        val recordedSnackbars = RecordingPaneSnackbarController()
        var threadDeletedCallbacks = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        lateinit var listState: LazyListState

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalPaneSnackbarController provides recordedSnackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "77001", title = "删除楼层集成测试", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                        onThreadDeleted = { threadDeletedCallbacks++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 滚至第 5 楼（index=4, offset=24）并压一次 PositionStack 验证 BackHandler 优先级
        composeRule.runOnIdle { vm.jumpToFloor(targetPid = "5005", targetTid = "77001") }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 4, scrollOffset = 24) }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.canPopPositionStack)
        val anchorIndexBefore = listState.firstVisibleItemIndex
        val anchorOffsetBefore = listState.firstVisibleItemScrollOffset

        // --- 路径 1：取消（点击「取消」与系统 Back，均 0 网络请求，不误弹 PositionStack）---
        composeRule.onNodeWithText("第 7 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()

        // 校验确认框文案逐字对齐 copy.md:145/150
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "删除这条回复？"))
        composeRule.onNode(hasText("删除这条回复？") and dialogPane).assertIsDisplayed()
        composeRule.onNode(hasText("删除后无法恢复。") and dialogPane).assertIsDisplayed()
        composeRule.onNode(hasText("取消") and dialogPane).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasText("删除") and dialogPane).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.assertGlassSurfaceCountAtMost(2)

        // 点取消 -> 0 网络请求
        composeRule.onNode(hasText("取消") and dialogPane).performClick()
        composeRule.waitForIdle()
        assertEquals("取消删除不得发起任何网络请求", 0, server.requestCount)
        assertEquals(0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)

        // 再打开按系统 Back -> 关闭确认框，0 网络请求，不误弹 PositionStack
        composeRule.onNodeWithText("第 7 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("按返回取消删除不得发起任何网络请求", 0, server.requestCount)
        assertEquals(0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        assertTrue("关闭删除确认框不得误弹 PositionStack", vm.uiState.value.canPopPositionStack)

        // --- 路径 2：失败（确认中显示「正在删除…」并禁用重复点击；服务端错误 + 网络错误均保留楼层并原地提示）---
        composeRule.onNodeWithText("第 7 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("删除") and dialogPane).performClick()

        awaitWallCondition(5_000L) { deleteStarted.count == 0L }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("正在删除…") and dialogPane).assertIsDisplayed()
        composeRule.onNode(hasText("取消") and dialogPane).assertIsNotEnabled()
        releaseDelete.countDown()

        awaitWallCondition(5_000L) {
            composeRule.onAllNodesWithText("无权删除已锁定楼层").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("无权删除已锁定楼层") and dialogPane).assertIsDisplayed()
        assertTrue("删除失败时目标楼层 5007 必须保留在列表中", vm.uiState.value.floors.any { it.postId == "5007" })
        assertNotNull("删除失败不得误删 edit:5007 草稿", runBlocking { realStack.draftDao.observe(floor7DraftKey.value).first() })

        // 再试一次 -> HTTP 500 映射为「网络连接失败」（绝不泄漏 HTTP 500）
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        awaitWallCondition(5_000L) {
            composeRule.onAllNodesWithText("网络连接失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("网络连接失败") and dialogPane).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("500", substring = true).fetchSemanticsNodes().size)
        assertTrue("网络失败时目标楼层 5007 必须继续保留", vm.uiState.value.floors.any { it.postId == "5007" })

        // --- 路径 3a：普通楼层删除成功 -> 移除 5007、阅读位零跳动、清理 edit:5007 草稿、严禁弹撤销 Snackbar ---
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        awaitWallCondition(5_000L) {
            vm.uiState.value.floors.none { it.postId == "5007" } && vm.uiState.value.deleteDialog == null
        }
        composeRule.waitForIdle()
        assertFalse("删除成功后 5007 必须从列表移除", vm.uiState.value.floors.any { it.postId == "5007" })
        assertEquals("删除下方楼层后当前首可见楼层索引必须保持不变", anchorIndexBefore, listState.firstVisibleItemIndex)
        assertEquals("删除下方楼层后当前首可见楼层像素偏移必须保持不变", anchorOffsetBefore, listState.firstVisibleItemScrollOffset)
        assertNull("删除楼层成功后其 edit:<pid> 草稿必须一并清理", runBlocking { realStack.draftDao.observe(floor7DraftKey.value).first() })
        assertTrue("删除不可撤销，严禁弹出任何撤销 Snackbar", recordedSnackbars.events.none { it.actionLabel == "撤销" || it.message.contains("撤销") })
        assertTrue("POST 表单必须含 delete=1", postRequests.last().contains("delete=1"))

        // --- 冷却期间再次尝试删除（路径 2b：30s 冷却原地提示，0 新网络请求）---
        composeRule.runOnIdle { listState.requestScrollToItem(0, 0) }
        composeRule.waitForIdle()
        val reqCountBeforeCooldown = server.requestCount
        composeRule.onNodeWithText("第 1 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        awaitWallCondition(5_000L) {
            composeRule.onAllNodesWithText("发送太快，请等", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals("冷却期间拦截删除不得发起网络请求", reqCountBeforeCooldown, server.requestCount)
        assertTrue("冷却拦截后首楼必须仍在列表", vm.uiState.value.floors.any { it.postId == "5001" })

        // --- 路径 3b：推进 30s 冷却到期后删除首楼 -> threadDeleted == true，触发整帖关闭回调，无撤销 Snackbar ---
        virtualClockMs.addAndGet(31_000L)
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        awaitWallCondition(5_000L) {
            composeRule.waitForIdle()
            threadDeletedCallbacks == 1
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isThreadDeleted)
        assertEquals(1, threadDeletedCallbacks)
        assertTrue("整帖删除同样严禁弹撤销 Snackbar", recordedSnackbars.events.none { it.actionLabel == "撤销" })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun nextAppRoot1280_editOwnFloorInRightPane_restoreUndo_submitRefreshesFloorWithoutScrollJump() {
        val harness = MultiPaneTestHarness()
        val detailRepo = EditableFloorsDetailRepository(ownUid = "42", floorCount = 14)
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo().copy(subject = "", text = "第 7 楼原始线上正文"))
            }
            submitHandler = { req ->
                val edit = req as PostSubmitRequest.EditPost
                detailRepo.updateFloorText(edit.pid, "第 7 楼刷新后的全新正文内容")
                PostSubmitResult.Success(tid = edit.tid, redirectedPage = detailRepo.snapshotPage(edit.tid, 1))
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val detailVms = mutableListOf<ThreadDetailViewModel>()
        val owner = ownerWithCustomDetailAndCompose(harness, detailRepo, postRepo, editorVm, detailVms)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 打开帖子并定位到第 7 楼压入 PositionStack
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        val detailVm = detailVms.single()
        composeRule.runOnIdle { detailVm.jumpToFloor("5007", "1001") }
        composeRule.waitForIdle()
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 长按第 7 楼 -> 点「编辑」-> 1280dp 右栏（680dp ≥ 560dp）内展开纯色编辑器，玻璃预算 ≤ 3
        composeRule.onNodeWithText("第 7 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.waitForIdle()

        assertTrue(editorVm.uiState.value.isOpen)
        val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("1280dp 编辑模式必须在右栏展开（≥ 600dp）", with(composeRule.density) { sendBounds.left.toDp().value } >= 600f)
        composeRule.assertGlassSurfaceCountAtMost(3)
        val glassAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(0, glassAudit.nestedLayerBackdropCount)

        // 与原文一致时「还原原文」隐藏；修改后出现；点「还原原文」弹「已还原原文 | 撤销」；点「撤销」精确恢复
        assertEquals(0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("正文").performTextInput("追加修改内容")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已还原原文").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.body.serialize().contains("追加修改内容"))

        // 返回优先关闭编辑页（保存草稿并弹「已保存草稿 | 放弃」），绝不误弹 PositionStack
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        assertTrue("关闭编辑页后 PositionStack 必须保持完好", detailVm.uiState.value.canPopPositionStack)
        assertNotNull(postRepo.draftFor(DraftKey.edit("5007")))
        composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
        composeRule.onNodeWithText("放弃").assertIsDisplayed()

        // 待「已保存草稿」Snackbar 自然收起后，重新从楼层菜单点「编辑」自动恢复草稿并提交 -> 原地刷新第 7 楼不跳位
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 7 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()

        assertFalse(editorVm.uiState.value.isOpen)
        assertNull("编辑提交成功后必须清除 edit:5007 草稿", postRepo.draftFor(DraftKey.edit("5007")))
        assertNull("原地刷新楼层不得触发滚动跳转", detailVm.uiState.value.pendingScroll)
        assertTrue("原地刷新后 PositionStack 必须保持完好", detailVm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithText("第 7 楼刷新后的全新正文内容", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun nextAppRoot749_editOpensCenteredModal_andFirstFloorDeleteClosesRightPaneAndRefreshesForum() {
        val harness = MultiPaneTestHarness()
        val detailRepo = EditableFloorsDetailRepository(ownUid = "42", floorCount = 5)
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo().copy(subject = "原首楼标题六字", text = "原首楼正文内容"))
            }
            submitHandler = { req ->
                when (req) {
                    is PostSubmitRequest.DeletePost -> PostSubmitResult.Success(tid = req.tid, threadDeleted = true)
                    else -> PostSubmitResult.Success(tid = "1001")
                }
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithCustomDetailAndCompose(harness, detailRepo, postRepo, editorVm, mutableListOf())

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 749dp 紧凑三栏打开帖子 -> 长按 1 楼点「编辑」-> 居中模态覆盖全三栏（左缘 < 80dp，宽 ≤ 680dp），玻璃 ≤ 3
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.waitForIdle()

        val backBounds = composeRule.onAllNodesWithContentDescription("返回").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].fetchSemanticsNode().boundsInRoot
        }
        assertTrue("749dp 编辑页必须居中模态展开", with(composeRule.density) { backBounds.left.toDp().value } in 16f..90f)
        composeRule.assertGlassSurfaceCountAtMost(3)
        composeRule.runOnIdle { editorVm.requestClose() }
        composeRule.waitForIdle()

        // 长按 1 楼点「删除」确认 -> threadDeleted == true -> 清空右栏回到占位并触发中栏论坛列表刷新
        val refreshBefore = harness.threadListRepo.refreshCalls.get()
        composeRule.onNodeWithText("第 1 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "删除这条回复？"))
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        composeRule.waitForIdle()

        assertNull("首楼整帖删除后多栏必须清空 selectedThread", harness.hostViewModel.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
        assertTrue("整帖删除后必须触发论坛列表刷新", harness.threadListRepo.refreshCalls.get() > refreshBefore)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun nextAppRoot411_editOpensFullScreen_andFirstFloorDeleteReturnsToListAndRefreshesForum() {
        val harness = MultiPaneTestHarness()
        val detailRepo = EditableFloorsDetailRepository(ownUid = "42", floorCount = 5)
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo().copy(subject = "单栏首楼标题六字", text = "单栏首楼正文内容"))
            }
            submitHandler = { req ->
                when (req) {
                    is PostSubmitRequest.DeletePost -> PostSubmitResult.Success(tid = req.tid, threadDeleted = true)
                    else -> PostSubmitResult.Success(tid = "1001")
                }
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithCustomDetailAndCompose(harness, detailRepo, postRepo, editorVm, mutableListOf())

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 单栏 411dp 打开帖子 -> 长按 1 楼点「编辑」-> 全屏展开，0 玻璃
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.waitForIdle()

        val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("411dp 单栏编辑必须全屏贴右", with(composeRule.density) { sendBounds.right.toDp().value } >= 380f)
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.runOnIdle { editorVm.requestClose() }
        composeRule.waitForIdle()

        // 单栏首楼整帖删除 -> 返回帖子列表并触发刷新
        val refreshBefore = harness.threadListRepo.refreshCalls.get()
        composeRule.onNodeWithText("第 1 楼本人正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "删除这条回复？"))
        composeRule.onNode(hasText("删除") and dialogPane).performClick()
        composeRule.waitForIdle()

        assertNull("单栏首楼整帖删除后必须清空 selectedThread 返回列表", harness.hostViewModel.state.value.selectedThread)
        composeRule.onNodeWithText("测试帖子标题 1001").assertIsDisplayed()
        assertTrue("单栏整帖删除后必须触发论坛列表刷新", harness.threadListRepo.refreshCalls.get() > refreshBefore)
    }

    private fun ownerWithCustomDetailAndCompose(
        harness: MultiPaneTestHarness,
        detailRepo: ThreadDetailRepository,
        postRepo: net.jejer.hipda.data.post.PostRepository,
        editorVm: FullEditorViewModel,
        createdDetails: MutableList<ThreadDetailViewModel>,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(detailRepo, harness.gateway, harness.authRepo, postRepository = postRepo).also(createdDetails::add) as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    private fun awaitWallCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(25)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("等待条件超时 (${timeoutMs}ms)", condition())
    }

    private class EditableFloorsDetailRepository(
        private val ownUid: String,
        floorCount: Int,
    ) : ThreadDetailRepository {
        private val floorTexts = LinkedHashMap<String, String>().apply {
            for (i in 1..floorCount) {
                val pid = "${5000 + i}"
                put(pid, if (i == 1 || i == 7) "第 $i 楼本人正文内容，支持编辑与删除测试。" else "第 $i 楼坛友正文内容，用于验证滚动阅读位置。")
            }
        }

        fun updateFloorText(pid: String, newText: String) {
            floorTexts[pid] = newText
        }

        fun snapshotPage(tid: String, page: Int): ThreadDetailPage {
            val floors = floorTexts.entries.mapIndexed { idx, (pid, text) ->
                val floorNum = idx + 1
                val isOwn = floorNum == 1 || floorNum == 7
                PostFloor(
                    postId = pid, floor = floorNum, page = page,
                    uid = if (isOwn) ownUid else "${2000 + floorNum}",
                    author = if (isOwn) "本人账号" else "坛友$floorNum",
                    avatarUrl = null, timePost = "2026-11-26 12:${floorNum.toString().padStart(2, '0')}",
                    isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain(text)))),
                )
            }
            return ThreadDetailPage(
                tid = tid, fid = 2, title = "编辑与删除集成测试主题", page = page, lastPage = 1,
                floors = floors, postsPerPageEstimate = 20,
                meta = DiscuzPageMeta(DiscuzSessionInfo(true, ownUid, "本人账号"), "fh_e6b", null, PromptCounts()),
                hasPostList = true,
            )
        }

        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            ThreadDetailLoadResult.Success(snapshotPage(tid, page), filter, forumName, ownUid)

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val res = loadPage(tid ?: "77001", 1) as ThreadDetailLoadResult.Success
            return res.copy(targetPostId = pid)
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = floorsFlow.map { l -> l.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }

    private class InMemoryDraftDao : DraftDao {
        private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
        override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
        override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
        override suspend fun delete(key: String) { store.value = store.value - key }
    }

    private fun createIdleGateway() = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
}
