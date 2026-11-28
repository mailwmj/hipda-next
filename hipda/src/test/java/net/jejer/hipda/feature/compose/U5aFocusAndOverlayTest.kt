package net.jejer.hipda.feature.compose

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.DefaultImageUploadRepository
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.ImageCompressor
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.verticalHingeLayoutInfo
import net.jejer.hipda.feature.app.withWindowLayoutFlow
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
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

/**
 * U5a 验收 1（自动聚焦与弹键盘、展开全屏不收键盘）与验收 2（只剩右栏一致：完整编辑器形态与快速回复限宽/对齐）全链路测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class U5aFocusAndOverlayTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private class RecordingKeyboardController : SoftwareKeyboardController {
        val showCalls = AtomicInteger(0)
        val hideCalls = AtomicInteger(0)
        override fun show() { showCalls.incrementAndGet() }
        override fun hide() { hideCalls.incrementAndGet() }
        fun reset() { showCalls.set(0); hideCalls.set(0) }
    }

    private val emojiSlot: @androidx.compose.runtime.Composable (QuickReplyEmojiPanelHost) -> Unit = { host ->
        EmojiPanelRendererImpl().Content(host, Modifier)
    }

    private fun isNodeFocused(matcher: SemanticsMatcher): Boolean =
        composeRule.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    /** 验收 1(a)：快速回复输入「abc」点「展开全屏」→ 完整编辑器正文自动获得焦点、内容「abc」光标在末尾且触发 show()；覆盖全屏/居中模态/右栏三种形态。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1a_quickReplyExpandToFullEditor_autoFocusesBodyAtEnd_andAllPlacementsFocus() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val kb = RecordingKeyboardController()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalSoftwareKeyboardController provides kb,
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
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("回复 楼层作者1").performTextInput("abc")
        composeRule.waitForIdle()
        val showsBeforeExpand = kb.showCalls.get()
        val hidesBeforeExpand = kb.hideCalls.get()

        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        assertTrue(editorVm.uiState.value.isOpen)
        assertEquals("展开全屏交接路径不得调用 keyboardController.hide()", hidesBeforeExpand, kb.hideCalls.get())
        assertTrue("打开完整编辑器后必须调用 keyboardController.show()", kb.showCalls.get() > showsBeforeExpand)
        assertEquals("abc", editorVm.uiState.value.body.serialize())
        assertEquals(3, editorVm.uiState.value.body.activeSelectionStart)
        assertEquals(3, editorVm.uiState.value.body.activeSelectionEnd)

        val bodyField = composeRule.onNodeWithContentDescription("回复 楼层作者1")
        bodyField.assertIsFocused()
        val selRange = bodyField.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("光标必须位于交接文本「abc」末尾", TextRange(3, 3), selRange)
    }

    /** 验收 1(a) 补充：CenteredModal 与 RightPane 两种大屏投放形态同样自动聚焦并弹键盘。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun acceptance1a_centeredModalAndRightPane_bothAutoFocusAndShowKeyboard() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        var placement by mutableStateOf(FullEditorPlacement.CenteredModal)
        val kb = RecordingKeyboardController()

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorPlacementFrame(placement, if (placement == FullEditorPlacement.CenteredModal) 749f else 1280f, false, {}) { isModal ->
                        FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609001", 2, "模态正文", 4, 4, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").assertIsFocused()
        assertTrue("CenteredModal 必须弹键盘", kb.showCalls.get() >= 1)

        val showsBeforeRightPane = kb.showCalls.get()
        placement = FullEditorPlacement.RightPane
        composeRule.runOnIdle {
            vm.requestClose(emitSavedSnackbar = false)
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609002", 2, "右栏正文", 4, 4, threadAuthor = "alice")))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 alice").assertIsFocused()
        assertTrue("RightPane 必须弹键盘", kb.showCalls.get() > showsBeforeRightPane)
    }

    /**
     * 验收 1(b)：
     * 1. 发新帖（无草稿）→ 标题框自动聚焦，在标题框输入第 1 个字后焦点仍留在标题框（不被正文抢走）；
     * 2. 带草稿标题且草稿 Flow 在首帧之后才发出（延迟假仓库）→ 草稿到达后正文自动聚焦。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1b_newThreadEmptySubjectFocusesSubjectAndStaysOnFirstChar_delayedDraftTitleMovesFocusToBody() {
        val draftGate = CompletableDeferred<DraftEntity?>()
        val baseRepo = FakePostRepository()
        val delayedDraftRepo = object : PostRepository by baseRepo {
            override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = when (key) {
                DraftKey.newThread("6") -> flow { emit(draftGate.await()) }
                else -> baseRepo.observeDraft(key)
            }
        }
        val authRepo = FakeQuickReplyAuthRepository()
        val vm = FullEditorViewModel(delayedDraftRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val kb = RecordingKeyboardController()

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = false)
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 无草稿发新帖（fid=2）：首帧标题框聚焦，输入第 1 个字后焦点仍停在标题框
        composeRule.runOnIdle { vm.open(FullEditorRequest.NewThread(initialFid = 2)) }
        composeRule.waitForIdle()
        val subjectField = composeRule.onNodeWithContentDescription("标题")
        val bodyField = composeRule.onNodeWithContentDescription("正文")
        subjectField.assertIsFocused()
        bodyField.assertIsNotFocused()
        assertTrue("无草稿发新帖聚焦标题后必须调 show()", kb.showCalls.get() >= 1)

        subjectField.performTextInput("测")
        composeRule.waitForIdle()
        assertEquals("测", vm.uiState.value.subject)
        subjectField.assertIsFocused()
        bodyField.assertIsNotFocused()

        // 2. 切至 fid=6（延迟草稿 Flow）：首帧草稿未到 → 标题框聚焦；草稿到达填入非空标题后 → 正文聚焦
        composeRule.runOnIdle {
            vm.requestClose(emitSavedSnackbar = false)
            vm.open(FullEditorRequest.NewThread(initialFid = 6))
        }
        composeRule.waitForIdle()
        subjectField.assertIsFocused()
        bodyField.assertIsNotFocused()
        val showsBeforeDraft = kb.showCalls.get()

        draftGate.complete(
            DraftEntity(
                key = DraftKey.newThread("6").value,
                subject = "延迟恢复草稿标题",
                typeId = "0",
                content = "延迟恢复正文内容",
                updatedAt = 1_000L,
            ),
        )
        composeRule.waitUntil(timeoutMillis = 5_000L) { vm.uiState.value.subject == "延迟恢复草稿标题" }
        composeRule.waitForIdle()

        bodyField.assertIsFocused()
        subjectField.assertIsNotFocused()
        assertTrue("延迟草稿填入标题切焦正文后应再次调 show()", kb.showCalls.get() > showsBeforeDraft)
        assertEquals(TextRange(8, 8), bodyField.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    /** 验收 1(c)：编辑（MockWebServer 返回原文）→ 加载中不崩溃、不抢焦点/不弹键盘；原文到达后正文自动聚焦并弹键盘。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1c_editPostMockWebServer_noShowWhileLoading_focusesBodyWhenOriginalArrives() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        val releaseOriginal = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return if (target.contains("post.php?action=edit")) {
                    releaseOriginal.await(10, TimeUnit.SECONDS)
                    gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(
                            formhash = "edit_fh_u5a",
                            uid = "10000",
                            hash = "edit_hash_u5a",
                            subject = "线上原始标题测试",
                            textarea = "线上原始楼层正文",
                            isDeletable = false,
                        ),
                    )
                } else {
                    MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clients = mutableListOf<OkHttpClient>()
        try {
            val clock = AtomicLong(1_700_000_000_000L)
            val cookieStore = InMemorySiteCookieStore(clock = { clock.get() })
            val credStore = PostTestCredentialsStore()
            val settingsRepo = PostTestSettingsRepository(AppSettings(forumServer = server.url("").toString().trimEnd('/')))
            runBlocking {
                val acc = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
                credStore.upsert(acc)
                credStore.setActive(acc.uid)
            }
            val primaryClient = HttpClientModule.provideOkHttpClient(
                siteCookieStore = cookieStore,
                userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
                transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
            ).also(clients::add)
            val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clients::add)
            val detector = ChallengeDetector()
            val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("no challenge")) }
            val primaryGw = SiteAccessGateway(primaryClient, detector, solver, { clock.get() }, scope, 5.seconds)
            val recoveryGw = SiteAccessGateway(recoveryClient, detector, solver, { clock.get() }, scope, 5.seconds)
            val authRepo = DefaultAuthRepository(credStore, cookieStore, settingsRepo, primaryGw, recoveryGw, { clock.get() }, scope)
            val draftDao = object : DraftDao {
                private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
                override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
                override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
                override suspend fun delete(key: String) { store.value = store.value - key }
            }
            val postRepo = DefaultPostRepository(ForumHttpClient(primaryGw, settingsRepo, authRepo), authRepo, settingsRepo, draftDao, { clock.get() }, scope)
            val uploadRepo = DefaultImageUploadRepository(primaryGw, settingsRepo, authRepo, ImageCompressor(context, { File(tempFolder.root, "work") }, Dispatchers.IO), { clock.get() })
            val vm = FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo, { clock.get() })
            val kb = RecordingKeyboardController()

            composeRule.setContent {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = false)
                    }
                }
            }
            composeRule.waitForIdle()

            // 加载原文期间：显示加载态、不崩溃、严禁调用 show()
            composeRule.runOnIdle {
                vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607099", pid = "53529999", page = 1, isFirstFloor = true, floorNumber = 1, author = "HiUser"))
            }
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isLoadingEditOriginal)
            composeRule.onNodeWithText("正在加载…").assertIsDisplayed()
            assertEquals("EditPost 加载原文期间不得调用 keyboardController.show()", 0, kb.showCalls.get())

            // 放行 MockWebServer 原文响应 → 正文挂载后自动聚焦并弹键盘
            releaseOriginal.countDown()
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (vm.uiState.value.editOriginal == null && vm.uiState.value.editLoadError == null && System.nanoTime() < deadlineNs) {
                Thread.sleep(20)
                composeRule.waitForIdle()
            }
            composeRule.waitForIdle()
            assertTrue("EditPost 原文必须成功加载，editLoadError=${vm.uiState.value.editLoadError}", vm.uiState.value.editOriginal != null)

            composeRule.onNodeWithContentDescription("正文").assertIsFocused()
            assertEquals("线上原始楼层正文", vm.uiState.value.body.serialize())
            assertTrue("EditPost 原文到达后必须调用 keyboardController.show()", kb.showCalls.get() >= 1)
        } finally {
            releaseOriginal.countDown()
            scope.cancel()
            clients.forEach {
                it.dispatcher.cancelAll()
                it.dispatcher.executorService.shutdownNow()
                it.connectionPool.evictAll()
            }
            server.close()
        }
    }

    /** 验收 1(d)：未登录打开（含先登录后退登的边界）均不得调用 keyboardController.show()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1d_guestOpeningEditor_neverCallsKeyboardShow() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository(initialAccount = null)
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val kb = RecordingKeyboardController()

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = false)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.open(FullEditorRequest.NewThread(initialFid = 2)) }
        composeRule.waitForIdle()
        assertEquals("未登录打开发新帖不得调用 show()", 0, kb.showCalls.get())

        composeRule.runOnIdle { vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609100", 2, "", 0, 0, threadAuthor = "linus_hi"))) }
        composeRule.waitForIdle()
        assertEquals("未登录打开回复不得调用 show()", 0, kb.showCalls.get())

        // 边界：先登录打开过一次（focusRequestVersion > 0）→ 关闭 → 退登 → 再次打开，同样不得调 show()
        composeRule.runOnIdle {
            authRepo.setSignedIn()
            vm.requestClose(emitSavedSnackbar = false)
            vm.open(FullEditorRequest.NewThread(initialFid = 2))
        }
        composeRule.waitForIdle()
        assertTrue(kb.showCalls.get() >= 1)

        val showsAfterLogout = kb.showCalls.get()
        composeRule.runOnIdle {
            vm.requestClose(emitSavedSnackbar = false)
            authRepo.setSignedOut()
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609101", 2, "", 0, 0, threadAuthor = "alice")))
        }
        composeRule.waitForIdle()
        assertEquals("退登后重开完整编辑器不得调用 show()", showsAfterLogout, kb.showCalls.get())
    }

    /** 验收 1(f)：QuickReplySheet 展开全屏不调用 keyboardController.hide()；下拉关闭 / 返回 / 点暗罩均调用 hide()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1f_quickReplyExpandSkipsKeyboardHide_whileDismissPathsStillHide() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 1), createIdleComposeGateway(), authRepo)
        val kb = RecordingKeyboardController()
        val handoffs = mutableListOf<FullEditorHandoff>()
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "2606199", title = "交接收键盘计数测试", fid = 2, author = "linus_hi"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            viewModel = detailVm,
                            quickReplyViewModel = qrVm,
                            onExpandToFullEditor = { handoffs += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 展开全屏：hideCalls 必须保持 0
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val hidesBeforeExpand = kb.hideCalls.get()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertEquals(1, handoffs.size)
        assertEquals("展开全屏路径不得调用 keyboardController.hide()", hidesBeforeExpand, kb.hideCalls.get())

        // 2. 下拉关闭：hideCalls +1
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").performTouchInput {
            down(center)
            moveBy(Offset(0f, with(composeRule.density) { 120.dp.toPx() }), delayMillis = 40L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("下拉关闭必须调用 hide()", hidesBeforeExpand + 1, kb.hideCalls.get())

        // 3. 系统返回关闭：hideCalls +1
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("按返回关闭必须调用 hide()", hidesBeforeExpand + 2, kb.hideCalls.get())

        // 4. 点暗罩关闭：hideCalls +1
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            click(Offset(with(composeRule.density) { 24.dp.toPx() }, with(composeRule.density) { 120.dp.toPx() }))
        }
        composeRule.waitForIdle()
        assertEquals("点暗罩关闭必须调用 hide()", hidesBeforeExpand + 3, kb.hideCalls.get())
    }

    /**
     * 验收 2（UI，749×559）：
     * - 正常态快速回复宽 = 411dp；
     * - 全屏阅读态快速回复宽 = 411dp ± 1 且水平居中，点左侧外部 x=24dp 暗罩可关闭，展开表情面板时表情槽宽度与面板一致；
     * - 全屏阅读态点「展开全屏」打开居中模态完整编辑器（含「关闭」而非「返回」）。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun acceptance2_fold749_fullScreenQuickReplyWidthCenteredScrimDismissEmojiWidth_andCenteredModalEditor() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val emojiRenderer = EmojiPanelRendererImpl()

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
                        emojiPanelSlot = { host -> emojiRenderer.Content(host, Modifier) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        fun measureQuickReplySheetBoundsDp(): Pair<Float, Float> {
            val sheet = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last()
            val leftDp = with(density) { sheet.boundsInRoot.left.toDp().value }
            val widthDp = with(density) { sheet.boundsInRoot.width.toDp().value }
            return leftDp to widthDp
        }

        // 1. 打开帖子 → 正常三栏下打开快速回复，量宽度 = 411dp（右栏原宽不变）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val (normalLeftDp, normalWidthDp) = measureQuickReplySheetBoundsDp()
        assertEquals("749×559 正常态快速回复宽应等于右栏宽 411dp", 411f, normalWidthDp, 1.5f)
        assertEquals("749×559 正常态快速回复应位于右栏(左缘 338dp)", 338f, normalLeftDp, 1.5f)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        // 2. 进入全屏阅读（只剩右栏）→ 打开快速回复：宽 = 411dp ± 1 且水平居中（left ≈ 169dp）
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        assertTrue(harness.hostViewModel.state.value.isFullScreenReading)

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val (fsLeftDp, fsWidthDp) = measureQuickReplySheetBoundsDp()
        assertEquals("749×559 全屏阅读下快速回复宽必须与正常态右栏 411dp 一致", 411f, fsWidthDp, 1.0f)
        assertEquals("749×559 全屏阅读下快速回复必须水平居中（(749-411)/2 = 169dp）", 169f, fsLeftDp, 1.5f)

        // 3. 展开表情面板：表情槽宽度与水平位置必须与快速回复面板一致
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        val emojiPanelBounds = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板"))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val emojiWidthDp = with(density) { emojiPanelBounds.width.toDp().value }
        val emojiLeftDp = with(density) { emojiPanelBounds.left.toDp().value }
        assertEquals("展开表情面板时表情槽宽度必须与快速回复面板一致", fsWidthDp, emojiWidthDp, 1.0f)
        assertEquals("展开表情面板时表情槽左缘必须与快速回复面板对齐", fsLeftDp, emojiLeftDp, 1.5f)

        // 4. 点面板左侧外部 x = 24dp 暗罩（高度与面板同区）→ 必须关闭快速回复面板
        val sheetCenterY = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
            .fetchSemanticsNodes().last().boundsInRoot.center.y
        composeRule.onRoot().performTouchInput {
            click(Offset(with(density) { 24.dp.toPx() }, sheetCenterY))
        }
        composeRule.waitForIdle()
        assertEquals("点面板左侧外部 x=24dp 暗罩必须关闭快速回复面板", 0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)

        // 5. 全屏阅读态下再开快速回复 → 点「展开全屏」→ 完整编辑器必须是 CenteredModal（「关闭」按钮且居中卡片）
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals("749×559 全屏阅读下完整编辑器应为居中模态（无返回箭头）", 0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
    }

    /** 验收 2（UI，铰链双栏全屏阅读）：面板停靠右半屏不跨物理铰链 [371..378]。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun acceptance2_hingeTwoPaneFullScreen_quickReplyDocksRightHalfWithoutCrossingHinge() {
        val separating = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(separating)) {
            val harness = MultiPaneTestHarness()
            val postRepo = FakePostRepository()
            val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
            val owner = ownerWithComposeVms(harness, postRepo, editorVm)

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
            val density = composeRule.density

            composeRule.onNodeWithText("测试帖子标题 1001").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("全屏阅读").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()

            val sheet = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot
            val leftDp = with(density) { sheet.left.toDp().value }
            val rightDp = with(density) { sheet.right.toDp().value }
            val widthDp = with(density) { sheet.width.toDp().value }
            assertTrue("铰链双栏全屏阅读下面板左缘($leftDp)不得跨越物理铰链右缘 378dp", leftDp >= 377f)
            assertEquals("铰链双栏全屏阅读下面板右缘应贴齐右半屏 749dp", 749f, rightDp, 1.5f)
            assertEquals("铰链双栏全屏阅读下面板宽应等于右半屏 371dp", 371f, widthDp, 1.5f)
        }
    }

    /** 验收 2（UI，933×696）：先切 BALANCED 再进全屏阅读 → 快速回复宽与完整编辑器形态与 BALANCED 正常态完全一致。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun acceptance2_w933_balancedThenFullScreen_matchesBalancedNormalWidthAndModalPlacement() {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(paneSplitLandscape = PaneSplit.BALANCED),
        )
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)

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
        val density = composeRule.density

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 1. BALANCED 正常态量宽（933 - 187 = 746, balanced middle = 373, right = 373）
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val balancedNormalWidthDp = with(density) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot.width.toDp().value
        }
        assertEquals("933×696 BALANCED 正常态快速回复宽应为 373dp", 373f, balancedNormalWidthDp, 1.5f)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        // 2. 进入全屏阅读后：快速回复宽仍为 373dp（与 BALANCED 正常态一致，而非 DEFAULT 的 448dp），全屏编辑仍为 CenteredModal
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val balancedFsWidthDp = with(density) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot.width.toDp().value
        }
        assertTrue(
            "933×696 BALANCED + 全屏阅读宽($balancedFsWidthDp)必须与 BALANCED 正常态($balancedNormalWidthDp)一致",
            abs(balancedFsWidthDp - balancedNormalWidthDp) <= 1.0f,
        )

        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
    }

    /** 修复轮 1 阻断 B1 回归（移植自 V-U5a probe3a）：发新帖标题输字后跨形态旋转 1280→749→411 与 StateRestorationTester 恢复不丢输入、正文不抢标题焦点且不重调 show()。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun regressionB1_rotationAndStateRestoration_preservesInputAndDoesNotReGrabFocus() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
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
        val showAfterResizeToModal = keyboardSpy.showCalls.get()
        val bodyFocusedAfterResizeToModal = isNodeFocused(bodyMatcher)
        val subjectFocusedAfterResizeToModal = isNodeFocused(subjectMatcher)
        winSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        val showAfterResizeToFull = keyboardSpy.showCalls.get()
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
                "restore(show=${keyboardSpy.showCalls.get()},subj=${isNodeFocused(subjectMatcher)},body=${isNodeFocused(bodyMatcher)})",
            showAfterResizeToModal == 0 && showAfterResizeToFull == 0 &&
                !bodyFocusedAfterResizeToModal && !bodyFocusedAfterResizeToFull &&
                !isNodeFocused(bodyMatcher) && keyboardSpy.showCalls.get() == 0,
        )
    }

    /** 修复轮 1 阻断 B1 回归（移植自 V-U5a probe3a2）：回复输字后 StateRestorationTester 恢复不丢输入且不重复调用 show()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun regressionB1_replyStateRestoration_preservesInputAndDoesNotReCallShow() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()

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
        assertTrue(keyboardSpy.showCalls.get() >= 1)

        keyboardSpy.reset()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("状态恢复后不得丢失输入", "abcdef", editorVm.uiState.value.body.serialize())
        composeRule.onNode(bodyMatcher).assertIsDisplayed()
        assertEquals("状态恢复后不应为同一已消费会话重复调用 show()", 0, keyboardSpy.showCalls.get())
    }

    /** 修复轮 1 建议 S1：访客态打开 NewThread / Reply 后原地登录成功，自动聚焦标题或正文并触发 show()。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun regressionS1_guestOpensNewThreadAndReplyThenLogsInInPlace_autoFocusesAndShowsKeyboard() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository(initialAccount = null)
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val kb = RecordingKeyboardController()

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides kb) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {})
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 访客态打开 NewThread -> 不弹键盘；原地登录后 -> 标题自动聚焦并调 show()
        composeRule.runOnIdle { vm.open(FullEditorRequest.NewThread(initialFid = 2)) }
        composeRule.waitForIdle()
        assertEquals("访客态开新帖不得调 show()", 0, kb.showCalls.get())
        assertFalse("访客态开新帖不得自动聚焦标题", isNodeFocused(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("标题"))))

        composeRule.runOnIdle { authRepo.setSignedIn() }
        composeRule.waitForIdle()
        assertTrue("访客态新帖原地登录后应自动聚焦标题", isNodeFocused(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("标题"))))
        assertTrue("访客态新帖原地登录后应调 show()", kb.showCalls.get() >= 1)

        // 2. 退出登录再访客态打开 Reply -> 不弹键盘；原地登录后 -> 正文自动聚焦并调 show()
        composeRule.runOnIdle {
            vm.requestClose(emitSavedSnackbar = false)
            authRepo.setSignedOut()
        }
        composeRule.waitForIdle()
        kb.reset()

        composeRule.runOnIdle {
            vm.open(FullEditorRequest.Reply(FullEditorHandoff(tid = "2607001", fid = 2, text = "guest reply", selectionStart = 11, selectionEnd = 11, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        assertEquals("访客态开回复不得调 show()", 0, kb.showCalls.get())

        composeRule.runOnIdle { authRepo.setSignedIn() }
        composeRule.waitForIdle()
        assertTrue("访客态回复原地登录后应自动聚焦正文", isNodeFocused(hasSetTextAction() and hasText("guest reply")))
        assertTrue("访客态回复原地登录后应调 show()", kb.showCalls.get() >= 1)
    }

    private fun ownerWithComposeVms(
        harness: MultiPaneTestHarness,
        postRepo: FakePostRepository,
        editorVm: FullEditorViewModel,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
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
}
