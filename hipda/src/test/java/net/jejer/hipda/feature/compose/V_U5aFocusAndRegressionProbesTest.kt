package net.jejer.hipda.feature.compose

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.DefaultImageUploadRepository
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.ImageCompressor
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V_U5aFocusAndRegressionProbesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class SpySoftwareKeyboardController : SoftwareKeyboardController {
        var showCalls = 0
        var hideCalls = 0

        override fun show() {
            showCalls += 1
        }

        override fun hide() {
            hideCalls += 1
        }

        fun reset() {
            showCalls = 0
            hideCalls = 0
        }
    }

    private val emojiSlot: @Composable (QuickReplyEmojiPanelHost) -> Unit = { host ->
        EmojiPanelRendererImpl().Content(host, Modifier)
    }

    private fun composeOwnerWithVms(
        harness: MultiPaneTestHarness,
        postRepo: PostRepository,
        editorVm: FullEditorViewModel,
    ): ViewModelStoreOwner {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, harness.authRepo, clockMs = { 10_000L }) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    private fun isNodeFocused(matcher: SemanticsMatcher): Boolean =
        composeRule.onNode(matcher).fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Focused) { false }

    private fun selectionOf(matcher: SemanticsMatcher): TextRange? =
        composeRule.onNode(matcher).fetchSemanticsNode().config
            .let { if (SemanticsProperties.TextSelectionRange in it) it[SemanticsProperties.TextSelectionRange] else null }

    /** Probe 1a: 快速回复输入后展开全屏 -> 正文块聚焦、光标在交接选区、keyboardController.hide() == 0、show() >= 1。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1a_quickReplyExpandFullEditor_focusesBodyAtHandoffSelection_andHideCountZero() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val keyboardSpy = SpySoftwareKeyboardController()

        composeRule.setContent {
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        val qrInput = composeRule.onNode(hasSetTextAction())
        qrInput.performTextInput("abc")
        composeRule.waitForIdle()
        qrInput.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 2, false) }
        composeRule.waitForIdle()

        keyboardSpy.reset()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        assertTrue("展开全屏后完整编辑器必须打开", editorVm.uiState.value.isOpen)
        assertEquals("展开全屏路径不得调用 keyboardController.hide()", 0, keyboardSpy.hideCalls)
        assertTrue("展开全屏后完整编辑器必须调用 keyboardController.show()", keyboardSpy.showCalls >= 1)

        val fullBodyMatcher = hasSetTextAction() and hasText("abc")
        assertTrue("展开全屏后正文文本块必须自动聚焦（未手动 RequestFocus）", isNodeFocused(fullBodyMatcher))
        assertEquals("交接选区应保留在 [1, 2]", TextRange(1, 2), selectionOf(fullBodyMatcher))
    }

    /** Probe 1b: 快速回复下拉关闭、点暗罩、系统返回路径均调用 keyboardController.hide() >= 1。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1b_quickReplyDismissPaths_callKeyboardHide() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val keyboardSpy = SpySoftwareKeyboardController()
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 1. 下拉把手关闭
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        keyboardSpy.reset()
        composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 320f, durationMillis = 120)
        }
        composeRule.waitForIdle()
        assertTrue("下拉关闭必须调用 keyboardController.hide()", keyboardSpy.hideCalls >= 1)

        // 2. 系统返回关闭
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        keyboardSpy.reset()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue("返回关闭必须调用 keyboardController.hide()", keyboardSpy.hideCalls >= 1)
    }

    /** Probe 1c: 发新帖无草稿聚焦标题，输入首字后焦点不跳到正文。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1c_newThreadNoDraft_focusesSubject_andTypingFirstCharKeepsSubjectFocused() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val keyboardSpy = SpySoftwareKeyboardController()
        vm.open(FullEditorRequest.NewThread(initialFid = 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardSpy) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, false, {}) { isModal ->
                        FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val subjectMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("标题"),
        )
        val bodyMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("正文"),
        )

        assertTrue("发新帖无草稿首帧应聚焦标题", isNodeFocused(subjectMatcher))
        assertFalse("发新帖无草稿首帧正文不得抢焦点", isNodeFocused(bodyMatcher))
        assertTrue("发新帖打开应弹键盘", keyboardSpy.showCalls >= 1)

        composeRule.onNode(subjectMatcher).performTextInput("a")
        composeRule.waitForIdle()

        assertTrue("输入第 1 个字后焦点必须仍在标题", isNodeFocused(subjectMatcher))
        assertFalse("输入第 1 个字后焦点不得跳到正文", isNodeFocused(bodyMatcher))
    }

    /** Probe 1d: 草稿 Flow 在首帧之后延迟发出 -> 到达后焦点切至正文。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1d_newThreadDelayedDraftFlow_switchesFocusToBodyAfterDraftArrives() {
        val delayedDraftDeferred = CompletableDeferred<DraftEntity?>()
        val baseRepo = FakePostRepository()
        val delayedRepo = object : PostRepository by baseRepo {
            override fun observeDraft(key: DraftKey): Flow<DraftEntity?> =
                if (key == DraftKey.newThread("2")) flow { emit(delayedDraftDeferred.await()) } else baseRepo.observeDraft(key)
        }
        val authRepo = FakeQuickReplyAuthRepository()
        val vm = FullEditorViewModel(delayedRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val keyboardSpy = SpySoftwareKeyboardController()
        vm.open(FullEditorRequest.NewThread(initialFid = 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardSpy) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, false, {}) { isModal ->
                        FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val subjectMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("标题"),
        )
        val bodyMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("正文"),
        )

        // 首帧草稿尚未发出，标题为空 -> 标题聚焦
        assertTrue("首帧草稿未到达时标题应先聚焦", isNodeFocused(subjectMatcher))
        assertFalse(isNodeFocused(bodyMatcher))

        // 首帧之后草稿 Flow 发出含标题+正文的草稿
        delayedDraftDeferred.complete(
            DraftEntity(
                key = DraftKey.newThread("2").value,
                subject = "延迟到达标题",
                typeId = "0",
                content = "延迟到达正文",
                quotePid = null,
                attachments = emptyList(),
                updatedAt = 2_000L,
            ),
        )
        composeRule.waitUntil(timeoutMillis = 3_000L) { vm.uiState.value.subject == "延迟到达标题" }
        composeRule.waitForIdle()

        assertEquals("延迟到达标题", vm.uiState.value.subject)
        assertTrue("草稿到达后必须切至正文聚焦", isNodeFocused(bodyMatcher))
        assertFalse("草稿到达后标题不再持有焦点", isNodeFocused(subjectMatcher))
        assertEquals("光标应落在恢复草稿正文末尾", TextRange(6, 6), selectionOf(bodyMatcher))
    }

    /** Probe 1e: 编辑（MockWebServer 返回原文）加载中不弹键盘不抢焦点；加载完成后正文聚焦并调用 show()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1e_editPostMockWebServer_noKeyboardDuringLoading_focusesBodyAndShowsAfterLoad() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualClock = AtomicLong(1_700_000_000_000L)
        val releaseEditGet = CountDownLatch(1)
        val clients = mutableListOf<OkHttpClient>()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val target = request.target.orEmpty()
                    if (target.contains("post.php?action=edit")) {
                        releaseEditGet.await(10, TimeUnit.SECONDS)
                        return gbkHtmlResponse(
                            PostTestFixtures.prePostHtml(
                                formhash = "edit_fh_probe",
                                uid = "10000",
                                subject = "Mock编辑原标题",
                                textarea = "Mock编辑原正文",
                            ),
                        )
                    }
                    return MockResponse.Builder().code(404).build()
                }
            }
            server.start()
            val cookieStore = InMemorySiteCookieStore { virtualClock.get() }
            val creds = PostTestCredentialsStore()
            val settingsRepo = PostTestSettingsRepository(
                AppSettings(forumServer = server.url("").toString().trimEnd('/')),
            )
            runBlocking {
                val acc = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
                creds.upsert(acc)
                creds.setActive(acc.uid)
            }
            val primaryClient = HttpClientModule.provideOkHttpClient(
                cookieStore,
                UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
                TransportInterceptor({ null }, { false }),
            ).also(clients::add)
            val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clients::add)
            val gw = SiteAccessGateway(primaryClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { virtualClock.get() }, scope, 5.seconds)
            val recGw = SiteAccessGateway(recoveryClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { virtualClock.get() }, scope, 5.seconds)
            val authRepo = DefaultAuthRepository(creds, cookieStore, settingsRepo, gw, recGw, { virtualClock.get() }, scope)
            runBlocking {
                withTimeout(5.seconds) {
                    authRepo.authState.first { it is net.jejer.hipda.data.auth.AuthState.SignedIn }
                }
            }
            val draftDao = object : DraftDao {
                private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
                override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
                override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
                override suspend fun delete(key: String) { store.value = store.value - key }
            }
            val postRepo = DefaultPostRepository(ForumHttpClient(gw, settingsRepo, authRepo), authRepo, settingsRepo, draftDao, { virtualClock.get() }, scope)
            val workDir = tempFolder.newFolder("probe1e_work")
            val uploadRepo = DefaultImageUploadRepository(gw, settingsRepo, authRepo, ImageCompressor(context, { workDir }, Dispatchers.IO), { virtualClock.get() })
            val vm = FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo) { virtualClock.get() }
            val keyboardSpy = SpySoftwareKeyboardController()

            composeRule.setContent {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardSpy) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, false, {}) { isModal ->
                            FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = "53529001", page = 1, isFirstFloor = true, floorNumber = 1, author = "HiUser"))
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("正在加载…").assertIsDisplayed()
            assertEquals("编辑原文加载中不得弹出键盘", 0, keyboardSpy.showCalls)
            assertEquals("加载中不得挂载正文输入控件", 0, composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

            releaseEditGet.countDown()
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (vm.uiState.value.editOriginal == null && vm.uiState.value.editLoadError == null && System.nanoTime() < deadlineNs) {
                Thread.sleep(20)
                composeRule.waitForIdle()
            }
            composeRule.waitForIdle()
            val st = vm.uiState.value
            assertTrue("编辑原文必须加载成功，实际 error=${st.editLoadError}, loggedIn=${st.isLoggedIn}", st.editOriginal != null)

            val bodyMatcher = hasSetTextAction() and hasText("Mock编辑原正文")
            assertTrue("编辑原文加载完成后正文必须聚焦", isNodeFocused(bodyMatcher))
            assertTrue("编辑原文加载完成后必须调用 keyboardController.show()", keyboardSpy.showCalls >= 1)
            assertEquals(TextRange(9, 9), selectionOf(bodyMatcher))
        } finally {
            releaseEditGet.countDown()
            scope.cancel()
            clients.forEach { c ->
                c.dispatcher.cancelAll()
                c.dispatcher.executorService.shutdownNow()
                c.connectionPool.evictAll()
            }
            server.close()
        }
    }

    /** Probe 1f: 未登录打开（NewThread/Reply/EditPost 与 QuickReplySheet）show() 计数均为 0。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1f_loggedOut_keyboardShowCountZero() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository(initialAccount = null)
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val keyboardSpy = SpySoftwareKeyboardController()
        var showQuickReply by mutableStateOf(false)

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardSpy) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        Box(Modifier.fillMaxSize()) {
                            if (editorVm.uiState.value.isOpen) {
                                FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, false, {}) { isModal ->
                                    FullEditorScreen(viewModel = editorVm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                                }
                            }
                            if (showQuickReply) {
                                QuickReplySheet(
                                    viewModel = qrVm,
                                    glassState = rememberHiGlassState(),
                                    onDismissFinished = { showQuickReply = false },
                                    onOpenLogin = {},
                                    emojiPanelSlot = emojiSlot,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { editorVm.open(FullEditorRequest.NewThread(2)) }
        composeRule.waitForIdle()
        assertEquals("未登录 NewThread show() 必须为 0", 0, keyboardSpy.showCalls)

        composeRule.runOnIdle { editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("2601001", 2, "", 0, 0))) }
        composeRule.waitForIdle()
        assertEquals("未登录 Reply show() 必须为 0", 0, keyboardSpy.showCalls)

        composeRule.runOnIdle { editorVm.open(FullEditorRequest.EditPost(2, "2601001", "9001")) }
        composeRule.waitForIdle()
        assertEquals("未登录 EditPost show() 必须为 0", 0, keyboardSpy.showCalls)

        composeRule.runOnIdle {
            editorVm.requestClose(emitSavedSnackbar = false)
            qrVm.open(QuickReplyTarget("2601001", 2, threadAuthor = "linus_hi"))
            showQuickReply = true
        }
        composeRule.waitForIdle()
        assertEquals("未登录 QuickReplySheet show() 必须为 0", 0, keyboardSpy.showCalls)
    }

    /** Probe 1g: 居中模态/全屏/右栏三种形态及同目标重开均自动聚焦并调 show()。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun probe1g_threePlacementsAndSameTargetReopen_autoFocusAndShow() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val keyboardSpy = SpySoftwareKeyboardController()
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 1280dp RightPane
        composeRule.runOnIdle { editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "abc", 3, 3, threadAuthor = "linus_hi"))) }
        composeRule.waitForIdle()
        val bodyMatcher = hasSetTextAction() and hasText("abc")
        assertTrue("RightPane 形态必须自动聚焦正文", isNodeFocused(bodyMatcher))
        assertTrue(keyboardSpy.showCalls >= 1)

        // 同目标重开（例如点击插入图片/重新唤起）递增 focusRequestVersion 并再次聚焦
        val verBefore = editorVm.uiState.value.focusRequestVersion
        keyboardSpy.reset()
        composeRule.runOnIdle { editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "abc", 3, 3, threadAuthor = "linus_hi"))) }
        composeRule.waitForIdle()
        assertEquals(verBefore + 1L, editorVm.uiState.value.focusRequestVersion)
        assertTrue(isNodeFocused(bodyMatcher))
        assertTrue(keyboardSpy.showCalls >= 1)

        // 2. 749dp CenteredModal 新会话
        winSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        keyboardSpy.reset()
        composeRule.runOnIdle {
            editorVm.requestClose(false)
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1002", 2, "xyz", 3, 3, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        assertTrue("CenteredModal 形态必须自动聚焦正文", isNodeFocused(hasSetTextAction() and hasText("xyz")))
        assertTrue(keyboardSpy.showCalls >= 1)

        // 3. 411dp FullScreen 新会话
        winSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        keyboardSpy.reset()
        composeRule.runOnIdle {
            editorVm.requestClose(false)
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1003", 2, "mno", 3, 3, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        assertTrue("FullScreen 形态必须自动聚焦正文", isNodeFocused(hasSetTextAction() and hasText("mno")))
        assertTrue(keyboardSpy.showCalls >= 1)
    }

    /** Probe 3a: 编辑器打开时旋转/StateRestorationTester 不丢输入、且不重复抢焦点。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun probe3a_rotationAndStateRestoration_preservesInputAndDoesNotReGrabFocus() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val keyboardSpy = SpySoftwareKeyboardController()
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))

        restorationTester.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 在发新帖（无草稿）中聚焦标题并输入内容（标题保持聚焦）
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        val subjectMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("标题"),
        )
        val bodyMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf("正文"),
        )
        assertTrue("发新帖初始应聚焦标题", isNodeFocused(subjectMatcher))
        composeRule.onNode(subjectMatcher).performTextInput("旋转不丢标题")
        composeRule.waitForIdle()
        assertTrue("输入标题后标题应仍聚焦", isNodeFocused(subjectMatcher))

        // 1. 旋转/缩窗（1280 -> 749 -> 411）：不丢输入、不重调 show()、标题不被正文抢焦
        keyboardSpy.reset()
        winSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        val showAfterResizeToModal = keyboardSpy.showCalls
        val bodyFocusedAfterResizeToModal = isNodeFocused(bodyMatcher)
        val subjectFocusedAfterResizeToModal = isNodeFocused(subjectMatcher)
        winSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        val showAfterResizeToFull = keyboardSpy.showCalls
        val bodyFocusedAfterResizeToFull = isNodeFocused(bodyMatcher)
        val subjectFocusedAfterResizeToFull = isNodeFocused(subjectMatcher)

        assertEquals("旋转后不得丢失已输入标题", "旋转不丢标题", editorVm.uiState.value.subject)

        // 2. StateRestorationTester 模拟 Activity 重建恢复（ViewModel 存活，Composable 重建）
        keyboardSpy.reset()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("状态恢复后不得丢失已输入标题", "旋转不丢标题", editorVm.uiState.value.subject)
        composeRule.onNodeWithText("旋转不丢标题").assertIsDisplayed()

        assertTrue(
            "旋转/状态恢复不得重复抢焦点或把标题焦点抢走: " +
                "resizeModal(show=$showAfterResizeToModal,subj=$subjectFocusedAfterResizeToModal,body=$bodyFocusedAfterResizeToModal) " +
                "resizeFull(show=$showAfterResizeToFull,subj=$subjectFocusedAfterResizeToFull,body=$bodyFocusedAfterResizeToFull) " +
                "restore(show=${keyboardSpy.showCalls},subj=${isNodeFocused(subjectMatcher)},body=${isNodeFocused(bodyMatcher)})",
            showAfterResizeToFull == 0 && !bodyFocusedAfterResizeToModal && !bodyFocusedAfterResizeToFull &&
                !isNodeFocused(bodyMatcher) && keyboardSpy.showCalls == 0,
        )
    }

    /** Probe 3a-2: 回复模式打开编辑器后输入正文，StateRestorationTester 状态恢复不丢输入、且不重复抢焦点调用 show()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3a2_replyStateRestoration_preservesInputAndDoesNotReCallShow() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val keyboardSpy = SpySoftwareKeyboardController()

        restorationTester.setContent {
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "abc", 3, 3, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        val bodyMatcher = hasSetTextAction() and hasText("abcdef", substring = true)
        composeRule.onNode(hasSetTextAction()).performTextInput("def")
        composeRule.waitForIdle()
        assertEquals("abcdef", editorVm.uiState.value.body.serialize())
        assertTrue(keyboardSpy.showCalls >= 1)

        keyboardSpy.reset()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("状态恢复后不得丢失输入", "abcdef", editorVm.uiState.value.body.serialize())
        composeRule.onNode(bodyMatcher).assertIsDisplayed()
        assertEquals("状态恢复后不应为同一已消费会话重复调用 show()", 0, keyboardSpy.showCalls)
    }

    /** Probe 3b: 快速回复（含 749×559 全屏限宽）与完整编辑器（411×891 全屏 & 749×559 居中模态）内表情与键盘互换底缘位移 <= 0.5dp。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe3b_emojiKeyboardSwap_bottomDisplacementWithinHalfDp() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            MultiPaneResizableWindowHost(DpSize(749.dp, 559.dp)) {
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchIme(imeDp: Int, navDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }

        // Part 1: 749×559 全屏阅读下 QuickReplySheet 表情 <-> 键盘互换底缘位移 <= 0.5dp
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        fun qrSheetBottomDp(): Float {
            val sheet = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            return with(density) { (sheet.positionInRoot.y + sheet.size.height).toDp().value }
        }
        dispatchIme(240)
        val qrBaseline = qrSheetBottomDp()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        assertEquals("QuickReply 切表情首帧底缘偏移", qrBaseline, qrSheetBottomDp(), 0.5f)
        for (ime in listOf(160, 80, 0)) {
            dispatchIme(ime)
            assertEquals("QuickReply IME->表情 ime=$ime 底缘偏移", qrBaseline, qrSheetBottomDp(), 0.5f)
        }
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertEquals("QuickReply 切键盘首帧底缘偏移", qrBaseline, qrSheetBottomDp(), 0.5f)
        for (ime in listOf(100, 240)) {
            dispatchIme(ime)
            assertEquals("QuickReply 表情->IME ime=$ime 底缘偏移", qrBaseline, qrSheetBottomDp(), 0.5f)
        }

        // Part 2: 749×559 全屏阅读下展开 FullEditorScreen (CenteredModal) 表情 <-> 键盘互换底缘位移 <= 0.5dp
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        fun editorToolbarBottomDp(): Float {
            val node = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            return with(density) { (node.positionInRoot.y + node.size.height).toDp().value }
        }
        dispatchIme(240)
        val edBaseline = editorToolbarBottomDp()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        assertEquals("FullEditor 切表情首帧底缘偏移", edBaseline, editorToolbarBottomDp(), 0.5f)
        for (ime in listOf(160, 80, 0)) {
            dispatchIme(ime)
            assertEquals("FullEditor IME->表情 ime=$ime 底缘偏移", edBaseline, editorToolbarBottomDp(), 0.5f)
        }
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertEquals("FullEditor 切键盘首帧底缘偏移", edBaseline, editorToolbarBottomDp(), 0.5f)
        for (ime in listOf(100, 240)) {
            dispatchIme(ime)
            assertEquals("FullEditor 表情->IME ime=$ime 底缘偏移", edBaseline, editorToolbarBottomDp(), 0.5f)
        }
    }
}
