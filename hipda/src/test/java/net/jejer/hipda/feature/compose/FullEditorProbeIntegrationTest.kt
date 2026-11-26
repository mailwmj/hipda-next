package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
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
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.OfflineSearchRepository
import net.jejer.hipda.feature.app.OfflineThreadDetailRepository
import net.jejer.hipda.feature.app.OfflineThreadListRepository
import net.jejer.hipda.feature.app.RecordingSettingsRepository
import net.jejer.hipda.feature.app.auditGlassModifiers
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V-E6 独立真实链路集成探针（不读写真实论坛，不依赖实现方 E6 测试文件）：
 * 1. 探针 1 + (a)：`NextAppRoot + ThreadDetailPane + QuickReplySheet` 中文+中间光标+引用 → `展开全屏` / `插入图片` 零丢失交接，共用 `reply:<tid>` 草稿无重复提示，旋转 + `StateRestorationTester` 进程重建后仍开且文本/光标/引用不丢；
 * 2. 探针 2 + (b)：`411 / 640 / 749 / 933 / 1280dp` 多栏形态与 `HiGlassMarker ≤ 3 / LayerBackdrop ≤ 2 / drawBackdrop ≤ 5` 预算，以及 `看图/登录 > 抽屉 > 编辑器 > 快速回复 > 位置栈` 返回优先级；
 * 3. 探针 3 + (d)：从 `今日新帖` 点 `发新帖` 未选版块不可提交（`R-D1`）、必填分类与正文/标题校验、`R-D6 S2` 清空旧草稿不复活、冷却倒计时与游客登录引导。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FullEditorProbeIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ProbeAuthRepository(
        initialAccount: SavedAccount? = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 100L),
    ) : AuthRepository {
        private val _current = MutableStateFlow(initialAccount)
        private val _state = MutableStateFlow<AuthState>(
            if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
        )
        override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initialAccount)).asStateFlow()
        override val authState: StateFlow<AuthState> = _state.asStateFlow()
        override val sessionEpoch: Long = 1L

        fun signOut() {
            _current.value = null
            _state.value = AuthState.SignedOut
        }

        fun signIn(account: SavedAccount = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 200L)) {
            _current.value = account
            _state.value = AuthState.SignedIn(account)
        }

        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) =
            LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() = signOut()
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class ProbePostRepository(
        private val nowMs: AtomicLong = AtomicLong(10_000L),
        initialDrafts: Map<String, DraftEntity> = emptyMap(),
    ) : PostRepository {
        private val drafts = MutableStateFlow(initialDrafts.toMap())
        private val _cooldownUntilMs = MutableStateFlow(0L)
        override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

        val prePostRequests = CopyOnWriteArrayList<PrePostRequest>()
        val submitRequests = CopyOnWriteArrayList<PostSubmitRequest>()
        val saveDraftCalls = CopyOnWriteArrayList<DraftEntity>()
        val deleteDraftCalls = CopyOnWriteArrayList<DraftKey>()

        var prePostHandler: suspend (PrePostRequest) -> PrePostResult = { req ->
            val typeOpts = if (req is PrePostRequest.NewThread && req.fid == 6) {
                linkedMapOf("0" to "选择分类", "1" to "手机", "2" to "配件")
            } else {
                emptyMap()
            }
            PrePostResult.Success(
                PrePostInfo(
                    formhash = "fh_probe",
                    uid = "1001",
                    hash = "hash_probe",
                    subject = null,
                    text = "",
                    quoteText = "",
                    isDeletable = false,
                    maxUploadFileSizeBytes = 2 * 1024 * 1024,
                    noticeAuthor = null,
                    noticeAuthorMsg = null,
                    noticeTrimStr = null,
                    existingImages = emptyList(),
                    existingAttachments = emptyList(),
                    typeId = "0",
                    typeOptions = typeOpts,
                    meta = DiscuzPageMeta(DiscuzSessionInfo(true, "1001", "linus_hi"), "fh_probe", null, PromptCounts()),
                ),
            )
        }

        var submitHandler: suspend (PostSubmitRequest) -> PostSubmitResult = { req ->
            when (req) {
                is PostSubmitRequest.NewThread -> PostSubmitResult.Success(tid = "88801")
                is PostSubmitRequest.ReplyThread -> PostSubmitResult.Success(tid = req.tid)
                is PostSubmitRequest.ReplyPost -> PostSubmitResult.Success(tid = req.tid)
                is PostSubmitRequest.QuotePost -> PostSubmitResult.Success(tid = req.tid)
                is PostSubmitRequest.EditPost -> PostSubmitResult.Success(tid = req.tid)
                is PostSubmitRequest.DeletePost -> PostSubmitResult.Success(tid = req.tid, threadDeleted = true)
            }
        }

        fun setCooldownUntil(untilMs: Long) {
            _cooldownUntilMs.value = untilMs
        }

        fun draftFor(key: DraftKey): DraftEntity? = drafts.value[key.value]

        override fun getRemainingCooldownSeconds(): Int {
            val rem = _cooldownUntilMs.value - nowMs.get()
            return if (rem <= 0L) 0 else ((rem + 999L) / 1_000L).toInt()
        }

        override suspend fun fetchPrePost(request: PrePostRequest, priority: AccessPriority): PrePostResult {
            prePostRequests += request
            return prePostHandler(request)
        }

        override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult {
            submitRequests += request
            val result = submitHandler(request)
            if (result is PostSubmitResult.Success) {
                request.draftKey?.let { deleteDraft(it) }
            }
            return result
        }

        override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = drafts.map { it[key.value] }

        override suspend fun saveDraft(
            key: DraftKey,
            subject: String,
            typeId: String,
            content: String,
            quotePid: String?,
            attachments: List<String>,
        ) {
            val cleaned = attachments.map { it.trim() }.filter { it.isNotEmpty() }
            if (subject.isBlank() && content.isBlank() && cleaned.isEmpty()) return
            val entity = DraftEntity(key.value, subject, typeId.trim(), content, quotePid?.trim()?.ifEmpty { null }, cleaned, nowMs.get())
            saveDraftCalls += entity
            drafts.value = drafts.value + (key.value to entity)
        }

        override suspend fun deleteDraft(key: DraftKey) {
            deleteDraftCalls += key
            drafts.value = drafts.value - key.value
        }
    }

    private class ProbeUploadRepository : ImageUploadRepository {
        private val seq = AtomicInteger(500)
        override fun uploadImage(
            input: ImageUploadInput,
            uid: String,
            hash: String,
            maxUploadBytes: Int?,
            priority: AccessPriority,
        ): Flow<ImageUploadProgress> = flow {
            emit(ImageUploadProgress.Compressing)
            emit(ImageUploadProgress.Uploading(512L, 1024L, 0.5f))
            emit(ImageUploadProgress.Uploading(1024L, 1024L, 1f))
            emit(ImageUploadProgress.Succeeded(seq.incrementAndGet().toString(), 1024L, wasCompressed = !input.original))
        }
    }

    private class ProbeHarness(
        initialSettings: AppSettings = AppSettings(),
        initialAccount: SavedAccount? = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 100L),
        initialDrafts: Map<String, DraftEntity> = emptyMap(),
    ) {
        val clockMs = AtomicLong(10_000L)
        val settingsRepo = RecordingSettingsRepository(initialSettings)
        val authRepo = ProbeAuthRepository(initialAccount)
        val postRepo = ProbePostRepository(clockMs, initialDrafts)
        val uploadRepo = ProbeUploadRepository()
        val smsUnread = MutableStateFlow(1)
        val notifyUnread = MutableStateFlow(2)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val threadListRepo = OfflineThreadListRepository()
        val searchRepo = OfflineSearchRepository()
        val threadDetailRepo = OfflineThreadDetailRepository()

        val createdHostVms = CopyOnWriteArrayList<NextAppHostViewModel>()
        val createdDetailVms = CopyOnWriteArrayList<ThreadDetailViewModel>()
        val createdEditorVms = CopyOnWriteArrayList<FullEditorViewModel>()

        val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) ->
                    NextAppHostViewModel(settingsRepo).also { createdHostVms += it } as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) ->
                    ForumViewModel(threadListRepo, searchRepo, settingsRepo, authRepo, gateway) as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(threadDetailRepo, gateway, authRepo).also { createdDetailVms += it } as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, authRepo, clockMs::get) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) ->
                    FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo, clockMs::get).also { createdEditorVms += it } as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }
    }

    private class StoreOwner(
        override val viewModelStore: ViewModelStore,
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory,
    ) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory

    private class FakeImagePickerOwner(
        private val resultToReturn: () -> List<Uri> = { emptyList() },
    ) : ActivityResultRegistryOwner {
        val launchedMimeTypes = CopyOnWriteArrayList<String>()
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launchedMimeTypes += input.toString()
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, resultToReturn() as O)
            }
        }
    }

    private val fakeImageViewer = object : ImageViewerRenderer {
        @Composable
        override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
            BackHandler(enabled = true, onBack = onDismiss)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), Alignment.Center) {
                Text("探针全屏看图浮层")
            }
        }
    }

    private val fakeLoginOverlay = object : LoginOverlayRenderer {
        @Composable
        override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
            BackHandler(enabled = true, onBack = onDismiss)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) {
                Text("探针登录浮层")
            }
        }
    }

    @Composable
    private fun ProbeAppRoot(
        harness: ProbeHarness,
        storeOwner: ViewModelStoreOwner,
        pickerOwner: FakeImagePickerOwner? = null,
        requestBus: FullEditorRequestBus? = null,
        glassEnvironment: GlassEnvironmentState = GlassEnvironmentState.RefractionAndBlur,
    ) {
        ProvideGlassEnvironment(glassEnvironment) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                val body: @Composable () -> Unit = {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        loginOverlayRenderer = fakeLoginOverlay,
                        imageViewerRenderer = fakeImageViewer,
                        fullEditorRequestBus = requestBus,
                    )
                }
                if (pickerOwner != null) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) { body() }
                } else {
                    body()
                }
            }
        }
    }

    /** 探针 1：真实 `NextAppRoot + ThreadDetailPane + QuickReplySheet` 中文 + 中间光标 + 引用交接全屏与插入图片。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1_quickReplyToFullEditor_expandAndInsertImage_zeroLoss_sharedDraft_noDuplicateSnackbar() {
        val harness = ProbeHarness()
        val storeOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        val pickerOwner = FakeImagePickerOwner()
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                ProbeAppRoot(harness, storeOwner, pickerOwner)
            }
        }
        composeRule.waitForIdle()

        // 打开帖子 1001，长按第 2 楼选「引用回复」
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("帖子 1001 第 2 楼正文内容，用于验证多栏阅读与状态迁移。").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()

        // 在快速回复输入框输入中文并把选区移到中间 (2..4)
        val quickInput = composeRule.onNodeWithContentDescription("引用第 2 楼 · 楼层作者2")
        quickInput.performTextInput("你好世界测试")
        composeRule.waitForIdle()
        quickInput.performTextInputSelection(TextRange(2, 4))
        composeRule.waitForIdle()

        val saveCallsBeforeHandoff = harness.postRepo.saveDraftCalls.size

        // 1. 点「展开全屏」：零丢失交接且不弹重复「已保存草稿」
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)
        assertEquals("交接不得触发多余 saveDraft", saveCallsBeforeHandoff, harness.postRepo.saveDraftCalls.size)

        val editorVm = harness.createdEditorVms.last()
        val editorState = editorVm.uiState.value
        assertTrue(editorState.isOpen)
        val quoteMode = editorState.mode as FullEditorMode.QuotePost
        assertEquals("1001", quoteMode.tid)
        assertEquals("1001_2", quoteMode.targetPid)
        assertEquals(2, quoteMode.targetFloor)
        assertEquals("楼层作者2", quoteMode.targetAuthor)
        assertEquals("你好世界测试", editorState.body.serialize())
        assertEquals(2, editorState.body.activeSelectionStart)
        assertEquals(4, editorState.body.activeSelectionEnd)

        // 验证 Compose TextFieldState 真实光标/选区确实位于 (2..4)：不点击坐标，直接聚焦输入「中」应替换「世界」得到「你好中测试」
        val editorBodyNode = composeRule.onNodeWithContentDescription("引用第 2 楼 · 楼层作者2")
        editorBodyNode.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        editorBodyNode.performTextInput("中")
        composeRule.waitForIdle()
        assertEquals("你好中测试", editorVm.uiState.value.body.serialize())
        assertEquals(3, editorVm.uiState.value.body.activeSelectionStart)

        // 按返回退出编辑器：静默保存 reply:1001 草稿（含引用楼层）并弹「已保存草稿」
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        val snackbarNode = composeRule.onNodeWithText("已保存草稿").assertIsDisplayed().fetchSemanticsNode()
        val savedDraft = harness.postRepo.draftFor(DraftKey.reply("1001"))
        assertNotNull(savedDraft)
        assertEquals("你好中测试", savedDraft?.content)
        assertEquals("1001_2", savedDraft?.quotePid)

        // 先让 Snackbar 自动收起（避免 16dp 底边距的 Snackbar 遮挡底部工具条），再重开快速回复验证草稿互通
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)
        assertNotNull(snackbarNode)

        // 2. 重开快速回复：自动恢复共用草稿「你好中测试」与引用楼层，再移光标至 2 点「插入图片」
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
        val qrInputReopened = composeRule.onNodeWithContentDescription("引用第 2 楼 · 楼层作者2")
        qrInputReopened.assertTextContains("你好中测试")
        qrInputReopened.performTextInputSelection(TextRange(2, 2))
        composeRule.waitForIdle()

        val saveCallsBeforePick = harness.postRepo.saveDraftCalls.size
        val snackbarsBeforePick = composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size
        composeRule.onNodeWithContentDescription("插入图片").performClick()
        composeRule.waitForIdle()

        assertTrue(editorVm.uiState.value.isOpen)
        assertEquals("你好中测试", editorVm.uiState.value.body.serialize())
        assertEquals(2, editorVm.uiState.value.body.activeSelectionStart)
        assertEquals(2, editorVm.uiState.value.body.activeSelectionEnd)
        assertEquals("1001_2", (editorVm.uiState.value.mode as FullEditorMode.QuotePost).targetPid)
        assertEquals(listOf("image/*"), pickerOwner.launchedMimeTypes.toList())
        assertEquals("点插入图片交接不得触发多余 saveDraft", saveCallsBeforePick, harness.postRepo.saveDraftCalls.size)
        assertEquals(snackbarsBeforePick, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)
    }

    /** 补充 (a)：快速回复 → 展开全屏 → 多轮旋转（单栏/横屏/三栏）→ 进程重建（`StateRestorationTester` + 全新 `ViewModelStore`）零丢失。 */
    @Test
    @Config(qualifiers = "w1280dp-h1024dp-xhdpi")
    fun probe2_supplementA_quickReplyToFullEditor_rotateAndProcessRecreation_preservesOpenTextCursorQuote() {
        val harness = ProbeHarness()
        val restorationTester = StateRestorationTester(composeRule)
        var currentOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        var windowSize by mutableStateOf(DpSize(411.dp, 891.dp))

        restorationTester.setContent {
            val owner = remember { StoreOwner(ViewModelStore(), harness.vmFactory).also { currentOwner = it } }
            MultiPaneResizableWindowHost(windowSize) {
                ProbeAppRoot(harness, owner)
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子并引用第 2 楼进入快速回复，输入中文并把光标放中间 (2, 2)
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("帖子 1001 第 2 楼正文内容，用于验证多栏阅读与状态迁移。").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()

        val qrInput = composeRule.onNodeWithContentDescription("引用第 2 楼 · 楼层作者2")
        qrInput.performTextInput("甲乙丙丁戊")
        composeRule.waitForIdle()
        qrInput.performTextInputSelection(TextRange(2, 2))
        composeRule.waitForIdle()

        // 2. 展开全屏
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        val editorVmBefore = harness.createdEditorVms.single()
        assertTrue(editorVmBefore.uiState.value.isOpen)
        assertEquals("甲乙丙丁戊", editorVmBefore.uiState.value.body.serialize())
        assertEquals(2, editorVmBefore.uiState.value.body.activeSelectionStart)

        // 3. 连续旋转与跨形态：411dp → 891×411 矮横屏 → 749×559 紧凑三栏 → 1280×800 平板 → 411dp
        listOf(
            DpSize(891.dp, 411.dp),
            DpSize(749.dp, 559.dp),
            DpSize(1280.dp, 800.dp),
            DpSize(411.dp, 891.dp),
        ).forEach { target ->
            windowSize = target
            composeRule.waitForIdle()
            assertTrue("旋转至 $target 后编辑器必须保持打开", editorVmBefore.uiState.value.isOpen)
            assertEquals("旋转至 $target 后文本不得丢失", "甲乙丙丁戊", editorVmBefore.uiState.value.body.serialize())
            assertEquals("旋转至 $target 后光标不得丢失", 2, editorVmBefore.uiState.value.body.activeSelectionStart)
            assertEquals("1001_2", (editorVmBefore.uiState.value.mode as FullEditorMode.QuotePost).targetPid)
        }

        // 4. 模拟系统进程重建：StateRestorationTester 销毁全部 remember + 换入全新 ViewModelStore（旧 VM 实例彻底丢弃）
        val hostCountBefore = harness.createdHostVms.size
        val editorCountBefore = harness.createdEditorVms.size
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertTrue("进程重建后必须创建全新 NextAppHostViewModel", harness.createdHostVms.size > hostCountBefore)
        assertTrue("进程重建后必须创建全新 FullEditorViewModel", harness.createdEditorVms.size > editorCountBefore)
        val restoredEditorVm = harness.createdEditorVms.last()
        assertTrue("进程重建后编辑器仍应保持打开", restoredEditorVm.uiState.value.isOpen)
        assertEquals("进程重建后文本不丢", "甲乙丙丁戊", restoredEditorVm.uiState.value.body.serialize())
        assertEquals("进程重建后光标起点不丢", 2, restoredEditorVm.uiState.value.body.activeSelectionStart)
        assertEquals("进程重建后光标终点不丢", 2, restoredEditorVm.uiState.value.body.activeSelectionEnd)
        val restoredQuote = restoredEditorVm.uiState.value.mode as FullEditorMode.QuotePost
        assertEquals("1001", restoredQuote.tid)
        assertEquals("1001_2", restoredQuote.targetPid)
        assertEquals(2, restoredQuote.targetFloor)
        assertEquals("楼层作者2", restoredQuote.targetAuthor)
        composeRule.onNodeWithText("引用回复").assertIsDisplayed()
        composeRule.onNodeWithText("引用第 2 楼 · 楼层作者2").assertIsDisplayed()

        // 验证重建后真实输入控件光标仍位于偏移 2：直接聚焦输入「X」得到「甲乙X丙丁戊」
        val restoredBodyInput = composeRule.onNodeWithContentDescription("引用第 2 楼 · 楼层作者2")
        restoredBodyInput.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        restoredBodyInput.performTextInput("X")
        composeRule.waitForIdle()
        assertEquals("甲乙X丙丁戊", restoredEditorVm.uiState.value.body.serialize())
        assertNotNull(currentOwner)
    }

    /** 探针 2 + 补充 (b) 布局与玻璃预算：验证 411/640/749/933/1280dp 形态尺寸与 `HiGlassMarker ≤ 3 / LayerBackdrop ≤ 2 / drawBackdrop ≤ 5`。 */
    @Test
    @Config(qualifiers = "w1280dp-h1024dp-xhdpi")
    fun probe3_multiPanePlacement_411_640_749_933_1280_andGlassBudget() {
        val harness = ProbeHarness()
        val storeOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        var windowSize by mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            Box(Modifier.requiredSize(windowSize)) {
                MultiPaneResizableWindowHost(windowSize) {
                    ProbeAppRoot(harness, storeOwner)
                }
            }
        }
        composeRule.waitForIdle()

        // 打开帖子并由快速回复进入完整编辑器
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density

        // 1. 411dp 单栏全屏（LocalHiGlassSuspended = true -> 0 玻璃）
        val bounds411 = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复"),
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(411f, with(density) { bounds411.width.toDp().value }, 2f)
        composeRule.assertGlassSurfaceCountAtMost(0)
        val audit411 = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(0, audit411.layerBackdropCount)
        assertEquals(0, audit411.drawBackdropCount)

        // 2. 640×1024 2栏居中模态：min(640 - 48, 680) = 592dp
        windowSize = DpSize(640.dp, 1024.dp)
        composeRule.waitForIdle()
        assertModalGeometryAndGlassBudget(windowWidthDp = 640f, expectedWidthDp = 592f)

        // 3. 749×559 紧凑三栏（右栏 411dp < 560dp）：覆盖全部三栏居中模态宽 min(749 - 48, 680) = 680dp
        windowSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        assertModalGeometryAndGlassBudget(windowWidthDp = 749f, expectedWidthDp = 680f)

        // 4. 933×696 完整三栏（右栏 448dp < 560dp）：覆盖全部三栏居中模态宽 min(933 - 48, 680) = 680dp
        windowSize = DpSize(933.dp, 696.dp)
        composeRule.waitForIdle()
        assertModalGeometryAndGlassBudget(windowWidthDp = 933f, expectedWidthDp = 680f)

        // 5. 1280×800 平板三栏（右栏 680dp >= 560dp）：在右栏内展开（left >= 600dp，width <= 680dp），中栏列表仍可见，底部工具条玻璃卸载
        windowSize = DpSize(1280.dp, 800.dp)
        composeRule.waitForIdle()
        val listRowNode = composeRule.onAllNodesWithText("测试帖子标题 1001").onFirst().assertIsDisplayed().fetchSemanticsNode()
        assertTrue("中栏列表项应位于左半侧 (< 600dp)", with(density) { listRowNode.boundsInRoot.right.toDp().value } <= 601f)
        val editorPaneBounds = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复"),
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val leftDp = with(density) { editorPaneBounds.left.toDp().value }
        val widthDp = with(density) { editorPaneBounds.width.toDp().value }
        assertTrue("1280dp 右栏展开左缘应 ≥ 600dp，实际=$leftDp", leftDp >= 599f)
        assertTrue("1280dp 右栏展开宽应 ≤ 680dp，实际=$widthDp", widthDp <= 681f)

        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val markers1280 = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("1280dp 右栏展开 HiGlassMarker 应 ≤ 3，实际=${markers1280.size}", markers1280.size <= 3)
        val audit1280 = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertTrue("1280dp 右栏展开 LayerBackdrop 应 ≤ 2，实际=${audit1280.layerBackdropCount}", audit1280.layerBackdropCount <= 2)
        assertEquals(0, audit1280.nestedLayerBackdropCount)
        assertTrue("1280dp 右栏展开 drawBackdrop 应 ≤ 5，实际=${audit1280.drawBackdropCount}", audit1280.drawBackdropCount <= 5)
    }

    private fun assertModalGeometryAndGlassBudget(windowWidthDp: Float, expectedWidthDp: Float) {
        val editorNode = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "回复"),
        ).assertIsDisplayed().fetchSemanticsNode()
        val widthDp = with(composeRule.density) { editorNode.boundsInRoot.width.toDp().value }
        val leftDp = with(composeRule.density) { editorNode.boundsInRoot.left.toDp().value }
        val expectedLeftDp = (windowWidthDp - expectedWidthDp) / 2f
        assertEquals("${windowWidthDp}dp 居中模态宽", expectedWidthDp, widthDp, 2f)
        assertEquals("${windowWidthDp}dp 居中模态水平居中左缘", expectedLeftDp, leftDp, 2f)
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val audit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertTrue("${windowWidthDp}dp LayerBackdrop ≤ 2，实际=${audit.layerBackdropCount}", audit.layerBackdropCount <= 2)
        assertEquals(0, audit.nestedLayerBackdropCount)
        assertTrue("${windowWidthDp}dp drawBackdrop ≤ 5，实际=${audit.drawBackdropCount}", audit.drawBackdropCount <= 5)
    }

    /** 补充 (b) 返回优先级：在 `1280dp` 实测 `看图/登录 > 编辑器 > 快速回复 > 位置栈`，以及 `749dp` 收起列表右栏展开时 `登录 > 抽屉 > 编辑器 > 位置栈`。 */
    @Test
    @Config(qualifiers = "w1280dp-h1024dp-xhdpi")
    fun probe4_supplementB_backPriority_viewerLogin_drawer_editor_quickReply_positionStack() {
        val harness = ProbeHarness()
        val storeOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        var windowSize by mutableStateOf(DpSize(1280.dp, 800.dp))
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(windowSize) {
                ProbeAppRoot(harness, storeOwner)
            }
        }
        composeRule.waitForIdle()

        // Part A (1280dp 右栏展开)：建立 PositionStack(页1->页3) + 打开 QuickReply + 从 ForumPane 点发新帖覆盖右栏
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()

        // 从中栏 ForumPane 点「发新帖」打开完整编辑器（此时底层 QuickReply 与 PositionStack 均仍在）
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        val editorVm = harness.createdEditorVms.single()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("标题").performTextInput("返回栈草稿标题")
        composeRule.waitForIdle()

        // 叠放 LoginOverlay：返回 1 先关登录浮层，编辑器保持打开
        composeRule.runOnIdle { harness.createdHostVms.single().openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("探针登录浮层").assertIsDisplayed()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("探针登录浮层").fetchSemanticsNodes().size)
        assertTrue("关闭登录浮层后编辑器必须保持打开", editorVm.uiState.value.isOpen)

        // 返回 2：关完整编辑器并保存草稿，底层 QuickReply 仍在
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        assertEquals(3, detailVm.uiState.value.page)

        // 返回 3：关 QuickReply，PositionStack 仍在
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 返回 4：出栈 PositionStack 回到第 1 页
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)

        // 清除「已保存草稿」Snackbar，防止其遮挡 ThreadBottomToolbar「回复」按钮
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()

        // Part B (749dp 收起列表 -> 右栏 685dp >= 560dp 右栏展开 + 240dp 模态抽屉)：看图/登录 > 抽屉 > 编辑器 > 位置栈
        windowSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) }
        }
        composeRule.waitForIdle()

        // 先打开完整编辑器（右栏内展开），再从 64dp 轨点开 240dp 模态抽屉
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val hasExpandBtn = composeRule.onAllNodesWithContentDescription("展开全屏").fetchSemanticsNodes().size
        assertTrue(
            "749x559 COLLAPSED 点击回复后应打开 QuickReply 并显示展开全屏；" +
                "threads=${harness.createdHostVms.single().state.value.selectedThread}, " +
                "editorOpen=${editorVm.uiState.value.isOpen}, " +
                "hasCollapse=${composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size}, " +
                "hasReplyBtn=${composeRule.onAllNodesWithText("回复").fetchSemanticsNodes().size}",
            hasExpandBtn == 1,
        )
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)

        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 在抽屉 + 编辑器之上打开 LoginOverlay
        composeRule.runOnIdle { harness.createdHostVms.single().openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("探针登录浮层").assertIsDisplayed()

        // 返回 A：先关 LoginOverlay，抽屉和编辑器仍在
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("探针登录浮层").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()
        assertTrue(editorVm.uiState.value.isOpen)

        // 返回 B：先关 240dp 抽屉，编辑器仍在
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        assertTrue("关闭抽屉后右栏编辑器必须保持打开", editorVm.uiState.value.isOpen)

        // 返回 C：再关编辑器
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
    }

    /** 补充 (d) + 探针 3：`今日新帖` 点 `发新帖` 未选版块不可提交（R-D1）、分类/标题/正文校验、冷却与游客引导、R-D6 S2 清空草稿不复活。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe5_supplementD_todayNewForumGuard_validation_cooldown_andDraftCleanup_RD6_S2() {
        val oldReplyKey = DraftKey.reply("1001")
        val harness = ProbeHarness(
            initialDrafts = mapOf(oldReplyKey.value to DraftEntity(oldReplyKey.value, content = "旧回复草稿内容", updatedAt = 5_000L)),
        )
        val storeOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                ProbeAppRoot(harness, storeOwner)
            }
        }
        composeRule.waitForIdle()

        // 1. 切换到「今日新帖」并点顶栏「发新帖」
        composeRule.onNodeWithText("今日新帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()

        val editorVm = harness.createdEditorVms.single()
        assertTrue(editorVm.uiState.value.isOpen)
        assertNull("今日新帖进入时不得预选 fid", editorVm.uiState.value.selectedFid)
        assertTrue("未选有效版块前不得预取 PrePost", harness.postRepo.prePostRequests.isEmpty())

        // 填满合格标题和正文但不选版块，点发送 → 必须拦发并提示「请选择版块」
        composeRule.onNodeWithContentDescription("标题").performTextInput("超过六个字的合格新帖标题")
        composeRule.onNodeWithContentDescription("正文").performTextInput("超过六个字的合格新帖正文内容")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("发送").performClick()
        composeRule.waitForIdle()

        assertTrue("未选版块时严禁发起提交", harness.postRepo.submitRequests.isEmpty())
        composeRule.onNodeWithText("请选择版块").assertIsDisplayed()

        // 选择 Buy & Sell (fid = 6)，触发 PrePostRequest.NewThread(6) 并弹出分类下拉；未选分类点发送拦发提示「请选择分类」
        composeRule.onNodeWithContentDescription("版块").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Buy & Sell").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(PrePostRequest.NewThread(6)), harness.postRepo.prePostRequests.toList())

        composeRule.onNodeWithText("发送").performClick()
        composeRule.waitForIdle()
        assertTrue("未选必填分类时严禁发起提交", harness.postRepo.submitRequests.isEmpty())
        composeRule.onNodeWithText("请选择分类").assertIsDisplayed()

        // 验证标题太短与内容太短文案
        composeRule.onNodeWithContentDescription("分类").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("手机").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("标题").performTextReplacement("短标题")
        composeRule.onNodeWithText("发送").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("标题至少 6 个字").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("标题").performTextReplacement("足够长的测试新帖标题")
        composeRule.onNodeWithContentDescription("正文").performTextReplacement("五个字不够")
        composeRule.onNodeWithText("发送").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("内容至少 6 个字").assertIsDisplayed()
        assertTrue(harness.postRepo.submitRequests.isEmpty())

        // 未登录提示与恢复登录提交成功
        composeRule.runOnIdle { harness.authRepo.signOut() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录后即可操作").assertIsDisplayed()
        composeRule.runOnIdle { harness.authRepo.signIn() }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("正文").performTextReplacement("这是一段超过六个字的合格发新帖正文")
        composeRule.onNodeWithText("发送").performClick()
        composeRule.waitForIdle()

        val submitted = harness.postRepo.submitRequests.single() as PostSubmitRequest.NewThread
        assertEquals(6, submitted.fid)
        assertEquals("1", submitted.typeId)
        assertEquals(DraftKey.newThread("6"), submitted.draftKey)
        assertFalse(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithText("帖子已发出").assertIsDisplayed()

        // 清除「帖子已发出」Snackbar 防止遮挡底部工具条「回复」按钮
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()

        // 2. 验证 R-D6 S2：
        // 路径 A1：快速回复恢复旧草稿 reply:1001 并显示「已恢复上次草稿」，展开全屏保留文本与 hadPersistedDraft；手动删空退出必显式 deleteDraft 且重开不复活
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText("Discovery"),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("回复 楼层作者1").assertTextContains("旧回复草稿内容")
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertEquals("旧回复草稿内容", editorVm.uiState.value.body.serialize())
        assertTrue(editorVm.uiState.value.hadPersistedDraft)

        // 手动删空后退出
        composeRule.onNodeWithContentDescription("回复 楼层作者1").performTextReplacement("")
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue("删空旧草稿退出必须显式调用 deleteDraft", oldReplyKey in harness.postRepo.deleteDraftCalls)
        assertNull("旧草稿不得复活", harness.postRepo.draftFor(oldReplyKey))

        // 重开确认无草稿恢复横条
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)
        assertEquals("", editorVm.uiState.value.body.serialize())
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        // 411dp 单栏需先退出帖子详情回到列表页才能点顶栏「发新帖」
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()

        // 路径 A2：从新帖草稿直接打开完整编辑器 → 显示「已恢复上次草稿 [清空]」→ 点「清空」后退出重开不复活（R-D6 S2）
        val newThreadKey = DraftKey.newThread("2")
        composeRule.runOnIdle {
            runBlocking {
                harness.postRepo.saveDraft(
                    key = newThreadKey,
                    subject = "旧新帖草稿标题",
                    typeId = "0",
                    content = "旧新帖草稿正文",
                    quotePid = null,
                    attachments = emptyList(),
                )
            }
        }
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
        composeRule.onNodeWithText("清空").performClick()
        composeRule.waitForIdle()
        assertTrue("点清空必须显式 deleteDraft", newThreadKey in harness.postRepo.deleteDraftCalls)
        assertEquals("", editorVm.uiState.value.subject)
        assertEquals("", editorVm.uiState.value.body.serialize())
        assertEquals(0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)
        assertEquals("", editorVm.uiState.value.subject)
        assertEquals("", editorVm.uiState.value.body.serialize())
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()

        // 路径 B：冷却期 UI 倒计时与禁止提交
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        harness.postRepo.setCooldownUntil(harness.clockMs.get() + 12_000L)
        composeRule.waitForIdle()
        assertEquals(12, editorVm.uiState.value.cooldownSeconds)
        composeRule.onNodeWithContentDescription("12 秒后可发送").assertIsDisplayed()
        assertFalse(editorVm.uiState.value.canTapSend)
    }

    /** 诊断探针 (b-extra)：749dp 收起列表右栏编辑器与 240dp 模态抽屉同时打开并经 `StateRestorationTester` 重建后，按返回是否仍满足 `抽屉 > 编辑器`。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe4b_supplementB_drawerAboveRightPaneEditor_acrossStateRestoration() {
        val harness = ProbeHarness()
        val restorationTester = StateRestorationTester(composeRule)
        // 模拟配置变更 / Activity 重建：Compose 状态（抽屉开关、编辑器保存态）从 SavedInstanceState 恢复，ViewModelStore 保留宿主已选帖子
        val preservedStore = ViewModelStore()
        var backDispatcher: OnBackPressedDispatcher? = null

        restorationTester.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val owner = remember { StoreOwner(preservedStore, harness.vmFactory) }
            Box(Modifier.requiredSize(DpSize(749.dp, 559.dp))) {
                MultiPaneResizableWindowHost(DpSize(749.dp, 559.dp)) {
                    ProbeAppRoot(harness, owner)
                }
            }
        }
        composeRule.waitForIdle()

        // 先进帖子再收起中栏（避免进详情触发 COLLAPSED -> DEFAULT 自动复位），使右栏宽 685dp >= 560dp -> RightPane
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()
        assertTrue(harness.createdEditorVms.last().uiState.value.isOpen)

        // 重建后，抽屉与右栏编辑器同时恢复打开，抽屉覆盖在编辑器之上
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()
        val restoredEditorVm = harness.createdEditorVms.last()
        assertTrue(restoredEditorVm.uiState.value.isOpen)

        // 按返回 1：抽屉优先于编辑器关闭，不得关闭底层编辑器而把抽屉留着
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        val remainingDrawers = composeRule.onAllNodesWithTag(HiShellTestTags.SidebarModalDrawer).fetchSemanticsNodes()
        assertTrue(
            "按返回应优先关闭抽屉，但抽屉仍存在(${remainingDrawers.size})，底层编辑器 open=${restoredEditorVm.uiState.value.isOpen}",
            remainingDrawers.isEmpty(),
        )
        assertTrue("关闭抽屉后，完整编辑器应保持打开", restoredEditorVm.uiState.value.isOpen)
    }

    /** 诊断探针 (a-extra)：含已插入图片块后的第 2 段文本光标经 `StateRestorationTester` 重建是否保留在后段而非回跳到第 1 段。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe2b_supplementA_multiBlockInlineImageCursor_acrossStateRestoration() {
        val harness = ProbeHarness()
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            val owner = remember { StoreOwner(ViewModelStore(), harness.vmFactory) }
            Box(Modifier.requiredSize(DpSize(411.dp, 891.dp))) {
                MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                    ProbeAppRoot(harness, owner)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        val editorVm = harness.createdEditorVms.last()
        composeRule.runOnIdle {
            editorVm.onPickImages(listOf(Uri.parse("content://probe.test/img/1")))
        }
        composeRule.waitForIdle()
        // 输入 "前段后段"，把光标放在 "前段" 后 (offset 2)，插入图片，再在后段 "后段" 中间 (offset 1) 输入并停在那里
        val bodyField = composeRule.onNodeWithContentDescription("回复 楼层作者1")
        bodyField.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        bodyField.performTextInput("前段后段")
        composeRule.waitForIdle()
        bodyField.performTextInputSelection(TextRange(2, 2))
        composeRule.waitForIdle()
        val uploadedLocalId = editorVm.uiState.value.uploads.single().localId
        composeRule.runOnIdle { editorVm.onInsertUploadedImage(uploadedLocalId) }
        composeRule.waitForIdle()

        // 当前 activeTextBlockId 应是图片后的块 ("后段")，将光标置于 "后" 与 "段" 之间 (offset 1)
        val afterImgBlockId = editorVm.uiState.value.body.lastTextBlockId
        composeRule.runOnIdle { editorVm.onTextBlockSelectionChanged(afterImgBlockId, 1, 1) }
        composeRule.waitForIdle()
        assertEquals(afterImgBlockId, editorVm.uiState.value.body.activeTextBlockId)
        assertEquals(1, editorVm.uiState.value.body.activeSelectionStart)

        // 重建进程后检查光标仍在图片后的那一段，而非跳回图片前的第 1 段
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        val restoredVm = harness.createdEditorVms.last()
        assertEquals(
            "多段图文重建后光标应留在图片后的文本块 (${restoredVm.uiState.value.body.lastTextBlockId})，实际 active=${restoredVm.uiState.value.body.activeTextBlockId}",
            restoredVm.uiState.value.body.lastTextBlockId,
            restoredVm.uiState.value.body.activeTextBlockId,
        )
        assertEquals(1, restoredVm.uiState.value.body.activeSelectionStart)
    }

    /** 诊断探针：验证 `FullEditorRequestBus` 单例总线绑定 `NextAppRoot` 消费语义，并实测退出详情时 Snackbar 与底部「回复」按钮坐标。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe9_fullEditorRequestBus_consumesHandoff_andRecordsSnackbarVsReplyToolbarBounds() {
        val harness = ProbeHarness()
        val storeOwner = StoreOwner(ViewModelStore(), harness.vmFactory)
        val bus = FullEditorRequestBus()
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                ProbeAppRoot(harness, storeOwner, requestBus = bus)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val handoff = FullEditorHandoff(
            tid = "1001",
            fid = 2,
            text = "总线交接文本",
            selectionStart = 2,
            selectionEnd = 2,
            quotePid = "1001_2",
            quoteAuthor = "楼层作者2",
            quoteFloor = 2,
            openImagePickerImmediately = false,
        )
        composeRule.runOnIdle { bus.open(handoff) }
        composeRule.waitForIdle()

        assertNull("PendingFullEditorRequest 在 NextAppRoot 打开后必须被立即消费", bus.pendingRequest.value)
        val editorVm = harness.createdEditorVms.single()
        assertTrue(editorVm.uiState.value.isOpen)
        assertEquals("总线交接文本", editorVm.uiState.value.body.serialize())
        assertEquals(2, editorVm.uiState.value.body.activeSelectionStart)

        // 退出保存草稿，检查「已保存草稿」Snackbar 与 ThreadBottomToolbar「回复」按钮是否重叠（已知 E3 MultiPaneSnackbarHost 缺陷观测）
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        val snackbarNode = composeRule.onNodeWithText("已保存草稿").assertIsDisplayed().fetchSemanticsNode()
        val replyNodes = composeRule.onAllNodesWithText("回复").fetchSemanticsNodes()
        val overlapsReply = replyNodes.any { replyNode -> snackbarNode.boundsInRoot.overlaps(replyNode.boundsInRoot) }
        println(
            "PROBE9_SNACKBAR_BOUNDS=${snackbarNode.boundsInRoot} " +
                "REPLY_BOUNDS=${replyNodes.firstOrNull()?.boundsInRoot} OVERLAPS_REPLY=$overlapsReply",
        )
    }
}
