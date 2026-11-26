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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.observe
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
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.TestImageFactory
import net.jejer.hipda.data.post.decodeGbkFormPairs
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.threaddetail.DefaultThreadDetailRepository
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.OfflineSearchRepository
import net.jejer.hipda.feature.app.OfflineThreadListRepository
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * V-E6b 独立真实链路集成探针（离线 `MockWebServer` + 真实 `Room` + 真实 `DefaultPostRepository` /
 * `DefaultThreadDetailRepository` / `DefaultImageUploadRepository` / `DefaultAuthRepository` +
 * `NextAppRoot` + `ThreadDetailPane` + `FullEditorScreen` / `FloorDeleteConfirmDialog`）：
 * 严禁访问 `4d4y.com`，仅覆盖 `V-E6b.md` 探针 1–3 与补充项 (a)–(e)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VE6bRealPathProbesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var database: HiDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var virtualClockMs: AtomicLong
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        server = MockWebServer()
        server.start()
        database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        database.close()
        server.close()
    }

    /**
     * 探针 1 + 补充 (b)：
     * 在 `NextAppRoot + ThreadDetailPane` 真实链路长按本人楼层点 `编辑` -> `MockWebServer` 拉取原文（含 2 张已有图 `101`/`102`） ->
     * 初开不显示 `还原原文` -> 修改正文后显示 -> 点 `还原原文` 恢复并弹 `已还原原文 | 撤销` -> 点 `撤销` 恢复编辑态 ->
     * 改正文 + 删已有图 `101` + 传新图 `201`（保留未删图 `102`） -> 30s 冷却活跃时仍可提交 `EditPost`（无小尾巴） ->
     * 验证 `MockWebServer` 捕获表单字段与旧版 `PostHelper` 一致、删 `edit:<pid>` 草稿并原地刷新楼层不跳位。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1_and_b_realPathEditRestoreUndo_cooldownExempt_noTail_postHelperFields_inPlaceRefreshNoJump() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val harness = createRealHarness(
            initialSettings = AppSettings(
                forumServer = serverUrl(),
                tailEnabled = true,
                tailText = "绝不能带上的小尾巴",
                tailUrl = "https://example.com/tail",
            ),
        )
        val newPngUri = Uri.parse("content://net.jejer.hipda.test/media/edit_new_photo.png")
        val pngBytes = TestImageFactory.writeSolidPng(File(tempFolder.root, "edit_new.png"), 120, 90).readBytes()
        shadowOf(context.contentResolver).registerInputStream(newPngUri, ByteArrayInputStream(pngBytes))

        val capturedRequests = CopyOnWriteArrayList<CapturedHttpRequest>()
        var editedFloorTextOnServer = "这是第 4 楼的原始正文内容\n[attachimg]101[/attachimg]\n[attachimg]102[/attachimg]"
        val floors = (1..10).map { n ->
            SampleFloorSpec(
                pid = (10010 + n).toString(),
                floor = n,
                uid = if (n == 4) USER_A.uid else (30000 + n).toString(),
                author = if (n == 4) USER_A.username else "其他作者$n",
                bodyHtml = if (n == 4) "这是第 4 楼的原始正文内容" else "帖子 1001 第 $n 楼正文内容，用于占位检验滚动位置不跳动。",
            )
        }.toMutableList()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                val rawBody = request.body?.utf8().orEmpty()
                capturedRequests += CapturedHttpRequest(request.method, path, rawBody)
                return when {
                    path.startsWith("/forum/viewthread.php") -> gbkHtmlResponse(
                        buildThreadHtml(
                            tid = "1001",
                            fid = 2,
                            title = "测试帖子标题 1001",
                            page = 1,
                            lastPage = 1,
                            loggedInUser = USER_A,
                            floors = floors,
                        ),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(
                            formhash = "fh_edit_p1",
                            uid = USER_A.uid,
                            username = USER_A.username,
                            hash = "upload_hash_01",
                            subject = "",
                            textarea = editedFloorTextOnServer,
                            isDeletable = true,
                            existingImages = listOf("101", "102"),
                        ),
                    )
                    path.startsWith("/forum/misc.php?action=swfupload") -> MockResponse.Builder()
                        .code(200)
                        .body("DISCUZUPLOAD|0|201|1")
                        .build()
                    path.startsWith("/forum/post.php?action=edit") && request.method == "POST" -> {
                        val idx = floors.indexOfFirst { it.pid == "10014" }
                        if (idx >= 0) {
                            floors[idx] = floors[idx].copy(bodyHtml = "编辑提交后的第 4 楼新正文")
                        }
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", "/forum/viewthread.php?tid=1001&page=1")
                            .build()
                    }
                    path.startsWith("/forum/post.php?action=reply") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(formhash = "fh_cd_reply", uid = USER_A.uid, username = USER_A.username),
                    )
                    path.startsWith("/forum/post.php?action=reply") && request.method == "POST" -> MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", "/forum/viewthread.php?tid=1001&page=1")
                        .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        // 先触发一次普通回复使 PostRepository 进入 30 秒发帖冷却期，再预埋一条 edit:10014 草稿检验成功后删除
        runBlocking {
            val replyRes = harness.postRepo.submitPost(
                PostSubmitRequest.ReplyThread(tid = "9999", content = "触发冷却的测试回复内容"),
            )
            assertTrue("前置触发冷却应成功，实际=$replyRes", replyRes is PostSubmitResult.Success)
            assertTrue("冷却倒计时必须活跃 (>0s)", harness.postRepo.getRemainingCooldownSeconds() > 0)
            database.draftDao().upsert(
                DraftEntity(
                    key = DraftKey.edit("10014").value,
                    content = "旧编辑草稿将被覆盖或提交后删除",
                    updatedAt = 900L,
                ),
            )
            // 删除预设草稿以测纯原文首次打开，再在提交前验证草稿清理
            database.draftDao().delete(DraftKey.edit("10014").value)
        }
        val cooldownBeforeEdit = harness.postRepo.cooldownUntilMs.value

        val pickerRegistry = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I : Any?, O : Any?> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    dispatchResult(requestCode, listOf(newPngUri))
                }
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerRegistry) {
                harness.RenderRoot(DpSize(411.dp, 891.dp))
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子 1001，等待真实 MockWebServer 帖子详情加载完成
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("这是第 4 楼的原始正文内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 滚至第 3 楼附近，使第 3 楼在视口顶部、第 4 楼完整可见（index = 2 非首楼）
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performScrollToIndex(2)
        }
        composeRule.waitForIdle()
        val floor3TopBefore = composeRule.onNodeWithText("帖子 1001 第 3 楼正文内容", substring = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        // 2. 长按第 4 楼点「编辑」
        composeRule.onNodeWithText("这是第 4 楼的原始正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").assertIsDisplayed().performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodes(hasSetTextAction() and hasText("这是第 4 楼的原始正文内容")).fetchSemanticsNodes().isNotEmpty()
        }

        // 校验 UI 绝不暴露 [attachimg] 源码，且初开隐藏「还原原文」
        assertEquals(0, composeRule.onAllNodesWithText("[attachimg]", substring = true).fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithContentDescription("已插入图片").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)

        // 3. 修改正文 -> 「还原原文」立即出现；点击「还原原文」-> 还原并弹「已还原原文 | 撤销」；点「撤销」-> 恢复编辑态
        composeRule.onNode(hasSetTextAction() and hasText("这是第 4 楼的原始正文内容")).performTextReplacement("临时编辑改写后的正文")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("这是第 4 楼的原始正文内容")).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("已还原原文").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("临时编辑改写后的正文")).assertIsDisplayed()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()

        // 4. 补充 (b)：改正文 + 删一张已有图 (101) + 加一张新图 (201)，保留未删图 (102)
        composeRule.onNode(hasSetTextAction() and hasText("临时编辑改写后的正文")).performTextReplacement("编辑提交后的第 4 楼新正文")
        composeRule.waitForIdle()
        // 上传条在正文上方，第 0 个「移除图片」按钮对应已有图 101
        composeRule.onAllNodesWithContentDescription("移除图片")[0].performClick()
        composeRule.waitForIdle()
        // 点击底部工具栏「插入图片」选择并真实压缩上传新图 -> MockWebServer 返回 201
        composeRule.onAllNodesWithContentDescription("插入图片").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        try {
            waitUntilSettled(5_000L) {
                harness.editorVm.uiState.value.uploadedImageIds == listOf("102", "201")
            }
        } catch (t: Throwable) {
            val st = harness.editorVm.uiState.value
            val stacks = Thread.getAllStackTraces().entries
                .filter { (th, _) -> th.name.contains("DefaultDispatcher") || th.name.contains("OkHttp") || th.name.contains("arch_disk") }
                .joinToString("\n") { (th, tr) -> "${th.name}(${th.state}): ${tr.take(6).joinToString(" <- ")}" }
            throw AssertionError(
                "probe1 upload timeout: uploads=${st.uploads}, inlineError=${st.inlineError}, validation=${st.validationError}, prePost=${st.prePostInfo != null}, loggedIn=${st.isLoggedIn}, uploadEv=${harness.uploadRepoEvents}, reqs=${capturedRequests.map { it.method + " " + it.path }}\nSTACKS:\n$stacks",
                t,
            )
        }
        composeRule.waitForIdle()

        // 5. 先写入一条 edit:10014 草稿验证提交成功后会被真实删除；同时核实冷却期中仍可点击「发送」
        runBlocking {
            harness.postRepo.saveDraft(DraftKey.edit("10014"), content = "待清理编辑草稿")
        }
        assertTrue("编辑提交必须豁免 30s 冷却", harness.postRepo.getRemainingCooldownSeconds() > 0)
        assertEquals(0, harness.editorVm.uiState.value.effectiveCooldownSeconds)
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled().performClick()

        waitUntilSettled(5_000L) { !harness.editorVm.uiState.value.isOpen }
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("编辑提交后的第 4 楼新正文", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // 6. 校验 MockWebServer 捕获到的 EditPost POST 请求路径与字段与旧版 PostHelper 完全一致
        val editPosts = capturedRequests.filter {
            it.method == "POST" && it.path.startsWith("/forum/post.php?action=edit")
        }
        assertEquals(1, editPosts.size)
        val captured = editPosts.single()
        assertEquals(
            "/forum/post.php?action=edit&extra=&editsubmit=yes&mod=&fid=2&tid=1001&pid=10014&page=1",
            captured.path,
        )
        val pairs = decodeGbkFormPairs(captured.body)
        val fieldMap = pairs.groupBy({ it.first }, { it.second })
        assertEquals(listOf("fh_edit_p1"), fieldMap["formhash"])
        assertTrue(fieldMap["posttime"]?.singleOrNull()?.all { it.isDigit() } == true)
        assertEquals(listOf("0"), fieldMap["wysiwyg"])
        assertEquals(listOf("1"), fieldMap["usesig"])
        assertNull("编辑请求不得包含 delete=1", fieldMap["delete"])
        val submittedMsg = fieldMap["message"]?.single().orEmpty()
        assertTrue("正文应包含新文本，实际=$submittedMsg", submittedMsg.contains("编辑提交后的第 4 楼新正文"))
        assertTrue("未删已有图 102 必须保留在正文中，实际=$submittedMsg", submittedMsg.contains("[attachimg]102[/attachimg]"))
        assertTrue("新传图片 201 应附入正文，实际=$submittedMsg", submittedMsg.contains("[attachimg]201[/attachimg]"))
        assertFalse("已删图片 101 不得留在正文中，实际=$submittedMsg", submittedMsg.contains("[attachimg]101[/attachimg]"))
        assertFalse("编辑提交绝不能附加小尾巴，实际=$submittedMsg", submittedMsg.contains("绝不能带上的小尾巴"))
        assertEquals("仅新上传图片 201 应出现在 attachnew[][description]", listOf("201"), fieldMap["attachnew[][description]"])
        assertEquals("仅被删已有图 101 应出现在 attachdel[]", listOf("101"), fieldMap["attachdel[]"])
        assertEquals(
            setOf("formhash", "posttime", "wysiwyg", "usesig", "message", "attachnew[][description]", "attachdel[]"),
            fieldMap.keys,
        )
        assertEquals("编辑成功不得改动发帖冷却时间戳", cooldownBeforeEdit, harness.postRepo.cooldownUntilMs.value)

        // 7. 校验 edit:10014 草稿已从 Room 删除，且原地刷新楼层后阅读位置未跳动 (<= 1dp)
        val remainingDraft = runBlocking { database.draftDao().observe(DraftKey.edit("10014")).first() }
        assertNull("编辑提交成功后必须删除 edit:<pid> 草稿", remainingDraft)
        val floor3TopAfter = composeRule.onNodeWithText("帖子 1001 第 3 楼正文内容", substring = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val driftDp = with(composeRule.density) { abs(floor3TopAfter - floor3TopBefore).toDp().value }
        assertTrue("原地刷新编辑楼层后相邻第 3 楼距顶偏差必须 ≤ 1dp，实际=${driftDp}dp", driftDp <= 1f)
    }

    /**
     * 探针 2 + 补充 (e)：
     * 749dp 紧凑三栏下验证 `edit:<pid>` 草稿闭环与 `R-D6 S2`（未改 0 提示 0 保存、改后存草稿弹 `已保存草稿 | 放弃`、
     * 恢复旧草稿后点 `清空` 与手动删空退出均调用 `deleteDraft` 永不复活）及返回优先级
     * （`LoginOverlay` > `DeleteDialog` / `EditEditor` > `PositionStack` > `MultiPaneBackStack`）与三栏 `HiGlassMarker ≤ 3`。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe2_and_e_editDraftRD6S2_threePaneGlassBudget_andBackPriorityHierarchy() {
        val harness = createRealHarness()
        val capturedDeletes = AtomicInteger(0)
        val serverOriginalText = "三栏编辑原文基准内容"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                return when {
                    path.startsWith("/forum/viewthread.php") -> {
                        val page = if (path.contains("page=3")) 3 else 1
                        val floors = (1..5).map { n ->
                            SampleFloorSpec(
                                pid = (20000 + page * 10 + n).toString(),
                                floor = (page - 1) * 5 + n,
                                uid = if (n == 1) USER_A.uid else "30002",
                                author = if (n == 1) USER_A.username else "路人$n",
                                bodyHtml = "第${page}页第${n}楼正文内容",
                            )
                        }
                        gbkHtmlResponse(buildThreadHtml("1001", 2, "测试帖子标题 1001", page, 3, USER_A, floors))
                    }
                    path.startsWith("/forum/post.php?action=edit") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(
                            formhash = "fh_p2",
                            uid = USER_A.uid,
                            username = USER_A.username,
                            subject = if (path.contains("pid=20011")) "测试帖子标题 1001" else null,
                            textarea = serverOriginalText,
                            isDeletable = true,
                        ),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "POST" -> {
                        if (request.body?.utf8().orEmpty().contains("delete=1")) capturedDeletes.incrementAndGet()
                        MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=1001&page=1").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val fakeLogin = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) { Text("V-E6b 模拟登录浮层") }
            }
        }
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            harness.RenderRoot(DpSize(749.dp, 559.dp), loginOverlayRenderer = fakeLogin)
        }
        composeRule.waitForIdle()

        // 打开帖子并检查三栏玻璃预算 ≤ 3
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("第1页第1楼正文内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

        // 跳到末页，建立 PositionStack
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("第3页第1楼正文内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val detailVm = harness.createdDetailVms.single()
        assertTrue("跳页后 PositionStack 必须非空", detailVm.uiState.value.canPopPositionStack)

        // 长按 11 楼 (第3页第1楼, uid=USER_A, pid=20031) 验证 DeleteDialog 返回优先于 PositionStack 且 0 请求
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        assertEquals(0, capturedDeletes.get())
        assertTrue("关闭删除对话框不得误出 PositionStack", detailVm.uiState.value.canPopPositionStack)

        // 1) 未改退出：0 保存、0「已保存草稿」提示
        val pid = "20031"
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText(serverOriginalText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(harness.editorVm.uiState.value.isOpen)
        assertEquals(0, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)
        assertNull(runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() })
        assertTrue("未改退出编辑不得误出 PositionStack", detailVm.uiState.value.canPopPositionStack)

        // 2) 改后叠加 LoginOverlay 验证返回优先级：LoginOverlay > EditEditor(存草稿) > PositionStack > MultiPaneBackStack
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText(serverOriginalText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(serverOriginalText).performTextReplacement("三栏编辑草稿内容1")
        composeRule.waitForIdle()

        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("V-E6b 模拟登录浮层").assertIsDisplayed()

        // 返回 1：仅关闭 LoginOverlay，编辑器仍在
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("V-E6b 模拟登录浮层").fetchSemanticsNodes().size)
        assertTrue(harness.editorVm.uiState.value.isOpen)

        // 返回 2：关闭编辑器静默存 edit:<pid> 草稿并弹「已保存草稿 | 放弃」，PositionStack 保持不变
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        waitUntilSettled(5_000L) {
            runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() }?.content == "三栏编辑草稿内容1"
        }
        composeRule.waitForIdle()
        assertFalse(harness.editorVm.uiState.value.isOpen)
        composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
        composeRule.onNodeWithText("放弃").assertIsDisplayed()
        assertTrue("关闭编辑器不得误出 PositionStack", detailVm.uiState.value.canPopPositionStack)
        // 让「已保存草稿」Snackbar 自然退场，避免其底部浮层遮挡 FloorContextMenuOverlay 的「编辑」菜单项
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()

        // 3) 重开验证「已恢复上次草稿 | 清空」，点「清空」后退出显式 deleteDraft 且再开不复活
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        try {
            waitUntilSettled(5_000L) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (t: Throwable) {
            val st = harness.editorVm.uiState.value
            val dbDraft = runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() }
            throw AssertionError(
                "probe2 banner timeout: isOpen=${st.isOpen}, mode=${st.mode}, showBanner=${st.showDraftRestoredBanner}, hadDraft=${st.hadPersistedDraft}, loading=${st.isLoadingEditOriginal}, loadErr=${st.editLoadError}, body=${st.body.serialize()}, dbDraft=$dbDraft",
                t,
            )
        }
        composeRule.onNode(hasSetTextAction() and hasText("三栏编辑草稿内容1")).assertIsDisplayed()
        composeRule.onNodeWithText("清空").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        waitUntilSettled(5_000L) {
            runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() } == null
        }
        composeRule.waitForIdle()

        // 再开核实不复活旧草稿
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodes(hasSetTextAction() and hasText(serverOriginalText)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)

        // 4) 改后存草稿 -> 重开恢复后「手动删空」退出（R-D6 S2 核心）：必须删除 edit:<pid> 草稿且不复活
        composeRule.onNode(hasSetTextAction() and hasText(serverOriginalText)).performTextReplacement("三栏编辑草稿内容2")
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        waitUntilSettled(5_000L) {
            runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() }?.content == "三栏编辑草稿内容2"
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        try {
            waitUntilSettled(5_000L) {
                composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (t: Throwable) {
            val st = harness.editorVm.uiState.value
            val dbDraft = runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() }
            throw AssertionError(
                "probe2 banner timeout: isOpen=${st.isOpen}, mode=${st.mode}, showBanner=${st.showDraftRestoredBanner}, hadDraft=${st.hadPersistedDraft}, loading=${st.isLoadingEditOriginal}, loadErr=${st.editLoadError}, body=${st.body.serialize()}, dbDraft=$dbDraft",
                t,
            )
        }
        composeRule.onNode(hasSetTextAction() and hasText("三栏编辑草稿内容2")).performTextReplacement("")
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        waitUntilSettled(5_000L) {
            runBlocking { database.draftDao().observe(DraftKey.edit(pid)).first() } == null
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)

        // 再开核实彻底恢复原文且不复活旧草稿
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText(serverOriginalText).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithText("已恢复上次草稿").fetchSemanticsNodes().size)
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()

        // 5) 在 749dp 收起中栏使编辑进入 RightPane 投放态，并展开 240dp 模态抽屉检验抽屉 > RightPane EditEditor > PositionStack > MultiPaneBackStack
        composeRule.runOnIdle { runBlocking { harness.settingsRepo.setPaneSplitLandscape(PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第3页第1楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText(serverOriginalText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 返回 3：先关 240dp 模态抽屉，RightPane 编辑器保持打开
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag(HiShellTestTags.SidebarModalDrawer).fetchSemanticsNodes().size)
        assertTrue(harness.editorVm.uiState.value.isOpen)

        // 返回 4：关 RightPane 编辑器，PositionStack 保持不变
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(harness.editorVm.uiState.value.isOpen)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 返回 5：弹出 PositionStack 回到第 1 页
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("第1页第1楼正文内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(detailVm.uiState.value.canPopPositionStack)

        // 返回 6：多栏返回栈关闭帖子回到占位
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }

    /**
     * 探针 3 + 补充 (d)：
     * 在 `NextAppRoot + ThreadDetailPane` 真实链路验证删除自己楼层的三条路径（`MockWebServer` 离线）：
     * ① 取消（0 网络请求）；② 失败（保留楼层并原地提示，支持重试）；
     * ③ 成功删除第 5 楼（移除楼层、无撤销 Snackbar、相邻第 4 楼距顶偏差 `≤ 1dp` 阅读位置不跳、清理 `edit:<pid>` 草稿）及
     * 删除第 1 楼（`threadDeleted = true` 退回论坛列表并刷新）。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3_and_d_deletePostThreePaths_middleFloorNoJump_firstFloorReturnsToList() {
        val harness = createRealHarness()
        val capturedPosts = CopyOnWriteArrayList<Pair<String, String>>()
        var failNextDelete = true
        val initialFloors = (1..12).map { n ->
            SampleFloorSpec(
                pid = (10010 + n).toString(),
                floor = n,
                uid = if (n == 1 || n == 5) USER_A.uid else (40000 + n).toString(),
                author = if (n == 1 || n == 5) USER_A.username else "楼层作者$n",
                bodyHtml = "帖子 1001 第 $n 楼测试正文内容",
            )
        }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                val rawBody = request.body?.utf8().orEmpty()
                if (request.method == "POST") capturedPosts += path to rawBody
                return when {
                    path.startsWith("/forum/viewthread.php") -> gbkHtmlResponse(
                        buildThreadHtml("1001", 2, "测试帖子标题 1001", 1, 1, USER_A, initialFloors),
                    )
                    path.startsWith("/forum/forumdisplay.php") -> gbkHtmlResponse(
                        PostTestFixtures.loggedInHtml(USER_A.uid, USER_A.username, "<div id=\"threadlist\"></div>"),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(
                            formhash = "fh_del_1",
                            uid = USER_A.uid,
                            username = USER_A.username,
                            textarea = "待删除内容",
                            isDeletable = true,
                        ),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "POST" -> {
                        val pairs = decodeGbkFormPairs(rawBody).toMap()
                        if (failNextDelete) {
                            gbkHtmlResponse(
                                PostTestFixtures.loggedInHtml(
                                    USER_A.uid,
                                    USER_A.username,
                                    """<div class="alert_info">您当前无权删除或需要等待冷却</div>""",
                                ),
                            )
                        } else if (path.contains("pid=10015")) {
                            MockResponse.Builder()
                                .code(302)
                                .addHeader("Location", "/forum/viewthread.php?tid=1001&page=1")
                                .build()
                        } else if (path.contains("pid=10011") && pairs["delete"] == "1") {
                            MockResponse.Builder()
                                .code(302)
                                .addHeader("Location", "/forum/forumdisplay.php?fid=2")
                                .build()
                        } else {
                            MockResponse.Builder().code(400).build()
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        // 预埋第 5 楼草稿，验证删除成功时同步清理
        runBlocking {
            database.draftDao().upsert(DraftEntity(DraftKey.edit("10015").value, content = "第5楼草稿", updatedAt = 1_000L))
        }

        composeRule.setContent { harness.RenderRoot(DpSize(411.dp, 891.dp)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("帖子 1001 第 1 楼测试正文内容", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 滚到第 4 楼附近，使第 4、5、6 楼同屏可见，第 1 楼已滚出视口
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performScrollToIndex(3)
        }
        composeRule.waitForIdle()
        val floor4TopBefore = composeRule.onNodeWithText("帖子 1001 第 4 楼测试正文内容", substring = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithText("帖子 1001 第 5 楼测试正文内容", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("帖子 1001 第 6 楼测试正文内容", substring = true).assertIsDisplayed()

        // ① 取消路径：文案逐字校验 + 0 请求
        val countBeforeCancel = server.requestCount
        composeRule.onNodeWithText("帖子 1001 第 5 楼测试正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()
        composeRule.onNodeWithText("删除后无法恢复。").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        assertEquals("点击取消严禁发起任何网络请求", countBeforeCancel, server.requestCount)
        assertEquals(0, capturedPosts.size)

        // ② 失败路径：点击删除后返回服务端错误 -> 保留第 5 楼且原地提示错误
        composeRule.onNodeWithText("帖子 1001 第 5 楼测试正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("您当前无权删除或需要等待冷却").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, capturedPosts.size)
        assertEquals(listOf("1"), decodeGbkFormPairs(capturedPosts.single().second).toMap()["delete"]?.let(::listOf))
        assertTrue(
            "删除失败时第 5 楼必须保留",
            composeRule.onAllNodesWithText("帖子 1001 第 5 楼测试正文内容", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )

        // ③a 成功删除第 5 楼：列表移除、相邻第 4 楼距顶偏差 ≤ 1dp、无撤销 Snackbar、edit:10015 草稿清理
        failNextDelete = false
        composeRule.onNodeWithText("删除").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("帖子 1001 第 5 楼测试正文内容", substring = true).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("撤销").fetchSemanticsNodes().size)
        val floor4TopAfter = composeRule.onNodeWithText("帖子 1001 第 4 楼测试正文内容", substring = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val driftDp = with(composeRule.density) { abs(floor4TopAfter - floor4TopBefore).toDp().value }
        assertTrue("删除第 5 楼后第 4 楼阅读位置不得跳动 (≤ 1dp)，实际=${driftDp}dp", driftDp <= 1f)
        composeRule.onNodeWithText("帖子 1001 第 6 楼测试正文内容", substring = true).assertIsDisplayed()
        assertNull("删除第 5 楼后应清理 edit:10015 草稿", runBlocking { database.draftDao().observe(DraftKey.edit("10015")).first() })

        // ③b 删除第 1 楼 (threadDeleted = true)：推进虚拟时钟越过删除冷却窗口后删除第 1 楼，退回论坛列表并触发刷新
        virtualClockMs.addAndGet(31_000L)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performScrollToIndex(0)
        }
        composeRule.waitForIdle()
        val forumRefreshBefore = harness.threadListRepo.refreshCalls.get()
        composeRule.onNodeWithText("帖子 1001 第 1 楼测试正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()

        try {
            waitUntilSettled(5_000L) {
                harness.hostViewModel.state.value.selectedThread == null
            }
        } catch (t: Throwable) {
            val dSt = harness.createdDetailVms.last().uiState.value
            throw AssertionError(
                "probe3 floor1 delete timeout: selectedThread=${harness.hostViewModel.state.value.selectedThread}, isThreadDeleted=${dSt.isThreadDeleted}, delDialog=${dSt.deleteDialog}, floors=${dSt.floors.map { it.postId }}, posts=$capturedPosts, clock=${virtualClockMs.get()}, cd=${harness.postRepo.getRemainingCooldownSeconds()}",
                t,
            )
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("帖子 1001 第 4 楼测试正文内容", substring = true).fetchSemanticsNodes().size)
        assertTrue("首楼删帖后必须触发论坛列表刷新", harness.threadListRepo.refreshCalls.get() > forumRefreshBefore)
        composeRule.onNodeWithText("测试帖子标题 1001").assertIsDisplayed()
    }

    /**
     * 补充 (a)：
     * 非本人楼层长按菜单无 `编辑`/`删除`；通过真实 `DefaultAuthRepository.switchAccount` 切换账号（及退出登录）后，
     * 无需重载帖子，楼层长按菜单立即反映当前账号所有权变化。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probeA_nonOwnFloorMenuHidesEditDelete_andUpdatesImmediatelyOnAccountSwitch() {
        val harness = createRealHarness(seedSecondAccount = USER_B)
        var currentActiveUser = USER_A
        val floors = listOf(
            SampleFloorSpec(pid = "10011", floor = 1, uid = USER_A.uid, author = USER_A.username, bodyHtml = "账号A发表的1楼"),
            SampleFloorSpec(pid = "10012", floor = 2, uid = USER_B.uid, author = USER_B.username, bodyHtml = "账号B发表的2楼"),
            SampleFloorSpec(pid = "10013", floor = 3, uid = "30003", author = "UserC", bodyHtml = "第三方账号C发表的3楼"),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                return when {
                    path.startsWith("/forum/viewthread.php") -> gbkHtmlResponse(
                        buildThreadHtml("1001", 2, "测试帖子标题 1001", 1, 1, currentActiveUser, floors),
                    )
                    path.startsWith("/forum/logging.php?action=login") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.loginPageHtml("login_fh_switch"),
                    )
                    path.startsWith("/forum/logging.php?action=login") && request.method == "POST" -> {
                        currentActiveUser = USER_B
                        gbkHtmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，${USER_B.username}]]></root>""")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        composeRule.setContent { harness.RenderRoot(DpSize(411.dp, 891.dp)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("账号A发表的1楼", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 1) 当前为 UserA：3楼(UserC)与2楼(UserB)均无编辑/删除，1楼(UserA)有编辑/删除
        composeRule.onNodeWithText("第三方账号C发表的3楼", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("复制").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("账号B发表的2楼", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("复制").performClick()
        composeRule.waitForIdle()

        // 2) 长按 1 楼(UserA)菜单正处于打开状态：显示编辑/删除
        composeRule.onNodeWithText("账号A发表的1楼", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()

        // 3) 直接切换账号到 UserB（不重载帖子）：已打开的 1 楼菜单即时移除「编辑」与「删除」
        val switchRes = runBlocking { harness.authRepo.switchAccount(USER_B.uid) }
        assertTrue("switchAccount 应成功，实际=$switchRes", switchRes is LoginResult.Success)
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("复制").performClick()
        composeRule.waitForIdle()

        // 4) 重开 2 楼(UserB)菜单：即时出现「编辑」与「删除」
        composeRule.onNodeWithText("账号B发表的2楼", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()

        // 5) 退出登录后，已打开菜单即时隐藏「编辑」与「删除」
        runBlocking { harness.authRepo.logout() }
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
    }

    /**
     * 补充 (c)：
     * 编辑中发生进程重建（`StateRestorationTester.emulateSavedInstanceStateRestore()` 重建全新 `FullEditorViewModel` 与 `ThreadDetailViewModel`）：
     * ① 已改动状态下，修改后标题/多段图文正文、第二文本段内光标位置、`还原原文` 按钮状态全部无损保留，
     *    重建后按光标输入字符精确落入原光标处、点 `还原原文` 仍能还原且点 `发送` 可完成提交（含首楼 `subject` / `typeid`）；
     * ② 未改动状态下，重建后 `还原原文` 仍保持隐藏。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probeC_editInProgressProcessRecreation_preservesContentCursorAndRestoreOriginalState() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = createRealHarness()
        val capturedEdits = CopyOnWriteArrayList<Pair<String, String>>()
        val capturedAllReqs = CopyOnWriteArrayList<String>()
        val floors = listOf(
            SampleFloorSpec(
                pid = "10011",
                floor = 1,
                uid = USER_A.uid,
                author = USER_A.username,
                bodyHtml = "前段原文<br/>[图101]<br/>后段原文",
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                val rawBody = request.body?.utf8().orEmpty()
                capturedAllReqs += "${request.method} $path"
                if (request.method == "POST") capturedEdits += path to rawBody
                return when {
                    path.startsWith("/forum/viewthread.php") -> gbkHtmlResponse(
                        buildThreadHtml("1001", 2, "原始主楼标题", 1, 1, USER_A, floors),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "GET" -> gbkHtmlResponse(
                        PostTestFixtures.prePostHtml(
                            formhash = "fh_recreate",
                            uid = USER_A.uid,
                            username = USER_A.username,
                            subject = "原始主楼标题",
                            textarea = "前段原文\n[attachimg]101[/attachimg]\n后段原文",
                            isDeletable = true,
                            existingImages = listOf("101"),
                            typeOptions = listOf("0" to "分类", "56" to "技术", "57" to "求助"),
                            selectedTypeId = "56",
                        ),
                    )
                    path.startsWith("/forum/post.php?action=edit") && request.method == "POST" -> MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", "/forum/viewthread.php?tid=1001&page=1")
                        .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val createdEditorVms = mutableListOf<FullEditorViewModel>()
        restorationTester.setContent {
            val freshStore = remember { ViewModelStore() }
            val vmFactory = remember {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                        modelClass.isAssignableFrom(FullEditorViewModel::class.java) ->
                            FullEditorViewModel(harness.postRepo, harness.uploadRepo, harness.authRepo, harness.settingsRepo) {
                                virtualClockMs.get()
                            }.also(createdEditorVms::add) as T
                        else -> harness.vmFactory.create(modelClass)
                    }
                }
            }
            val scopedOwner = remember(freshStore) {
                object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                    override val viewModelStore: ViewModelStore = freshStore
                    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
                }
            }
            Box(Modifier.requiredSize(DpSize(411.dp, 891.dp))) {
                MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
                        ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
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
                }
            }
        }
        composeRule.waitForIdle()

        // 打开帖子并进入 1 楼编辑页
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodesWithText("前段原文", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("前段原文", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        waitUntilSettled(5_000L) {
            composeRule.onAllNodes(hasSetTextAction() and hasText("后段原文")).fetchSemanticsNodes().isNotEmpty()
        }

        // ① 未修改态先重建一次，校验「还原原文」保持隐藏
        assertEquals(0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals("未改编辑重建后「还原原文」必须保持隐藏", 0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)

        // ② 修改标题与第二文本块并定光标于偏移 3，显示「还原原文」后再次模拟进程重建
        composeRule.onNode(hasSetTextAction() and hasText("原始主楼标题")).performTextReplacement("进程重建后保留标题")
        composeRule.waitForIdle()
        val tailBlock = composeRule.onNode(hasSetTextAction() and hasText("后段原文"))
        tailBlock.performTextReplacement("后段已改ABC")
        composeRule.waitForIdle()
        val editedTail = composeRule.onNode(hasSetTextAction() and hasText("后段已改ABC"))
        editedTail.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        editedTail.performTextInputSelection(TextRange(3, 3))
        composeRule.waitForIdle()

        val vmBefore = createdEditorVms.last()
        assertEquals(1, vmBefore.uiState.value.body.activeTextBlockOrdinal())
        assertEquals(3, vmBefore.uiState.value.body.activeSelectionStart)
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        waitUntilSettled(5_000L) {
            createdEditorVms.last().uiState.value.prePostInfo != null
        }
        composeRule.waitForIdle()

        val vmAfter = createdEditorVms.last()
        assertTrue("进程重建必须创建全新 FullEditorViewModel 实例", vmAfter !== vmBefore)
        assertTrue("进程重建后编辑器必须保持打开", vmAfter.uiState.value.isOpen)
        assertEquals("进程重建后标题必须保留", "进程重建后保留标题", vmAfter.uiState.value.subject)
        assertEquals("进程重建后光标必须留在第 2 文本段", 1, vmAfter.uiState.value.body.activeTextBlockOrdinal())
        assertEquals("进程重建后光标起点必须保持为 3", 3, vmAfter.uiState.value.body.activeSelectionStart)
        assertEquals("进程重建后光标终点必须保持为 3", 3, vmAfter.uiState.value.body.activeSelectionEnd)
        assertTrue("进程重建后 canRestoreOriginal 必须保持 true", vmAfter.uiState.value.canRestoreOriginal)

        composeRule.onNode(hasSetTextAction() and hasText("进程重建后保留标题")).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("前段原文")).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("后段已改ABC")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()

        // 验证 UI TextField 真实光标也停在偏移 3：直接输入 "Z" 应插入在 "后段已" 之后
        composeRule.onNode(hasSetTextAction() and hasText("后段已改ABC")).performTextInput("Z")
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("后段已Z改ABC")).assertIsDisplayed()

        // 重建后点「还原原文」-> 仍可还原并撤销
        composeRule.onNodeWithText("还原原文").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("原始主楼标题")).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("后段原文")).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("还原原文").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("进程重建后保留标题")).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("后段已Z改ABC")).assertIsDisplayed()

        // 重建后直接提交 -> 使用新拉取的 formhash 成功发送并带齐 subject/typeid
        composeRule.onNodeWithText("发送").performClick()
        try {
            waitUntilSettled(5_000L) { !vmAfter.uiState.value.isOpen }
        } catch (t: Throwable) {
            val st = vmAfter.uiState.value
            val stacks = Thread.getAllStackTraces().entries
                .filter { (th, _) -> th.name.contains("DefaultDispatcher") || th.name.contains("OkHttp") || th.name.contains("arch_disk") }
                .joinToString("\n") { (th, tr) -> "${th.name}(${th.state}): ${tr.take(6).joinToString(" <- ")}" }
            throw AssertionError(
                "probeC submit timeout: isOpen=${st.isOpen}, submitting=${st.isSubmitting}, valErr=${st.validationError}, inlineErr=${st.inlineError}, subj=${st.subject}, type=${st.selectedTypeId}, hasCat=${st.hasSelectableCategories}, body=${st.body.serialize()}, prePost=${st.prePostInfo != null}, postEv=${harness.postRepoEvents}, allReqs=$capturedAllReqs, edits=$capturedEdits\nSTACKS:\n$stacks",
                t,
            )
        }
        assertEquals(1, capturedEdits.size)
        val submittedFields = decodeGbkFormPairs(capturedEdits.single().second).toMap()
        assertEquals("fh_recreate", submittedFields["formhash"])
        assertEquals("进程重建后保留标题", submittedFields["subject"])
        assertEquals("56", submittedFields["typeid"])
        assertTrue(submittedFields["message"].orEmpty().contains("后段已Z改ABC"))
    }

    // region Real Harness & Offline Discuz HTML Builders

    private data class SampleFloorSpec(
        val pid: String,
        val floor: Int,
        val uid: String,
        val author: String,
        val bodyHtml: String,
    )

    private data class CapturedHttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private inner class RealStackHarness(
        val settingsRepo: PostTestSettingsRepository,
        val authRepo: DefaultAuthRepository,
        val postRepo: net.jejer.hipda.data.post.PostRepository,
        val uploadRepo: net.jejer.hipda.data.post.ImageUploadRepository,
        val threadDetailRepo: DefaultThreadDetailRepository,
        val threadListRepo: OfflineThreadListRepository,
        val hostViewModel: NextAppHostViewModel,
        val forumViewModel: ForumViewModel,
        val editorVm: FullEditorViewModel,
        val postRepoEvents: CopyOnWriteArrayList<String>,
        val uploadRepoEvents: CopyOnWriteArrayList<String>,
    ) {
        val smsUnread = MutableStateFlow(0)
        val notifyUnread = MutableStateFlow(0)
        val createdDetailVms = mutableListOf<ThreadDetailViewModel>()
        val parentOwner: ViewModelStoreOwner

        val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostViewModel as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(threadDetailRepo, gateway, authRepo, postRepo).also(createdDetailVms::add) as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }

        private lateinit var gateway: SiteAccessGateway

        fun attachGateway(gw: SiteAccessGateway) {
            gateway = gw
        }

        init {
            val store = ViewModelStore()
            parentOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore: ViewModelStore = store
                override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
            }
        }

        @Composable
        fun RenderRoot(
            windowSize: DpSize,
            loginOverlayRenderer: LoginOverlayRenderer? = null,
            imageViewerRenderer: ImageViewerRenderer? = null,
        ) {
            Box(Modifier.requiredSize(windowSize)) {
                MultiPaneResizableWindowHost(windowSize) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                        ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                            NextAppRoot(
                                settingsFlow = settingsRepo.settings,
                                currentAccountFlow = authRepo.currentAccount,
                                smsUnreadCountFlow = smsUnread,
                                notifyUnreadCountFlow = notifyUnread,
                                loginOverlayRenderer = loginOverlayRenderer,
                                imageViewerRenderer = imageViewerRenderer,
                                hostViewModel = hostViewModel,
                                forumViewModel = forumViewModel,
                                fullEditorViewModel = editorVm,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createRealHarness(
        initialSettings: AppSettings = AppSettings(forumServer = serverUrl()),
        seedSecondAccount: SavedAccount? = null,
    ): RealStackHarness {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credentialsStore = PostTestCredentialsStore()
        val settingsRepo = PostTestSettingsRepository(initialSettings)
        runBlocking {
            credentialsStore.upsert(USER_A)
            if (seedSecondAccount != null) credentialsStore.upsert(seedSecondAccount)
            credentialsStore.setActive(USER_A.uid)
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) }
        val primaryGw = SiteAccessGateway(primaryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val recoveryGw = SiteAccessGateway(recoveryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val authRepo = DefaultAuthRepository(credentialsStore, cookieStore, settingsRepo, primaryGw, recoveryGw, { virtualClockMs.get() }, scope)
        runBlocking {
            // 等待 DefaultAuthRepository init 协程完成首帧账户加载
            authRepo.currentAccount.first { it?.uid == USER_A.uid }
        }
        val forumHttp = ForumHttpClient(primaryGw, settingsRepo, authRepo)
        val realPostRepo = DefaultPostRepository(forumHttp, authRepo, settingsRepo, database.draftDao(), { virtualClockMs.get() }, scope)
        val postRepoEvents = CopyOnWriteArrayList<String>()
        val postRepo = object : net.jejer.hipda.data.post.PostRepository by realPostRepo {
            override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult {
                postRepoEvents += "submit:start:${request::class.simpleName}"
                val res = realPostRepo.submitPost(request, priority)
                postRepoEvents += "submit:end:$res"
                return res
            }
        }
        val workDir = File(tempFolder.root, "upload_work_${System.nanoTime()}").apply { mkdirs() }
        val compressor = ImageCompressor(context, { workDir }, Dispatchers.IO)
        val realUploadRepo = DefaultImageUploadRepository(primaryGw, settingsRepo, authRepo, compressor, { virtualClockMs.get() })
        val uploadRepoEvents = CopyOnWriteArrayList<String>()
        val uploadRepo = object : net.jejer.hipda.data.post.ImageUploadRepository by realUploadRepo {
            override fun uploadImage(
                input: net.jejer.hipda.data.post.ImageUploadInput,
                uid: String,
                hash: String,
                maxUploadBytes: Int?,
                priority: AccessPriority,
            ) = realUploadRepo.uploadImage(input, uid, hash, maxUploadBytes, priority).onEach { p ->
                uploadRepoEvents += "upload:$p"
            }
        }
        val blacklistRepo = object : BlacklistRepository {
            private val _blocked = MutableStateFlow(emptySet<String>())
            override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
            override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null)
            override fun isBlocked(username: String): Boolean = false
            override suspend fun syncBlacklist(priority: AccessPriority) = BlacklistSyncResult.Success(BlacklistSnapshot(), true)
            override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority) =
                BlacklistMutationResult.Success(username, emptySet())
            override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority) =
                BlacklistMutationResult.Success(username, emptySet())
        }
        val threadDetailRepo = DefaultThreadDetailRepository(
            forumHttpClient = forumHttp,
            authRepository = authRepo,
            settingsRepository = settingsRepo,
            blacklistRepository = blacklistRepo,
            browseHistoryDao = database.browseHistoryDao(),
            readingPositionDao = database.readingPositionDao(),
            clock = { virtualClockMs.get() },
            maxBrowseHistory = 50,
        )
        val threadListRepo = OfflineThreadListRepository()
        val searchRepo = OfflineSearchRepository()
        val hostViewModel = NextAppHostViewModel(settingsRepo, threadDetailRepo)
        val forumViewModel = ForumViewModel(threadListRepo, searchRepo, settingsRepo, authRepo, primaryGw)
        val editorVm = FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo) { virtualClockMs.get() }
        return RealStackHarness(
            settingsRepo = settingsRepo,
            authRepo = authRepo,
            postRepo = postRepo,
            uploadRepo = uploadRepo,
            threadDetailRepo = threadDetailRepo,
            threadListRepo = threadListRepo,
            hostViewModel = hostViewModel,
            forumViewModel = forumViewModel,
            editorVm = editorVm,
            postRepoEvents = postRepoEvents,
            uploadRepoEvents = uploadRepoEvents,
        ).apply { attachGateway(primaryGw) }
    }

    private fun waitUntilSettled(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMs) {
            ShadowLooper.idleMainLooper()
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun serverUrl(): String = server.url("").toString().trimEnd('/')

    private fun buildThreadHtml(
        tid: String,
        fid: Int,
        title: String,
        page: Int,
        lastPage: Int,
        loggedInUser: SavedAccount,
        floors: List<SampleFloorSpec>,
    ): String {
        val pagesHtml = if (lastPage > 1) {
            """<div class="forumcontrol"><div class="pages"><strong>$page</strong><a href="viewthread.php?tid=$tid&amp;page=$lastPage" class="last">... $lastPage</a></div></div>"""
        } else {
            """<div class="forumcontrol"><div class="pages"><strong>$page</strong></div></div>"""
        }
        val postsHtml = floors.joinToString("\n") { f ->
            """
            <div id="post_${f.pid}"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=${f.uid}">${f.author}</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a><em>${f.floor}</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 10:${f.floor.toString().padStart(2, '0')}</em></div></div></div>
                <div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">${f.bodyHtml}</td></tr></tbody></table></div></div></div>
              </td>
            </tr></tbody></table></div>
            """.trimIndent()
        }
        return PostTestFixtures.loggedInHtml(
            uid = loggedInUser.uid,
            username = loggedInUser.username,
            body = """
                <div id="nav"><a href="index.php">4D4Y</a> &raquo; <a href="forumdisplay.php?fid=$fid">Discovery</a> &raquo; $title</div>
                <div id="wrap">
                  <input type="hidden" name="tid" value="$tid" />
                  $pagesHtml
                  <div id="postlist">
                    $postsHtml
                  </div>
                </div>
            """.trimIndent(),
        )
    }

    // endregion

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val USER_A = SavedAccount(
            uid = "10001",
            username = "UserA",
            passwordMd5 = computeLegacyPasswordMd5("pwA"),
            lastLoginAt = 1_000L,
        )

        private val USER_B = SavedAccount(
            uid = "20002",
            username = "UserB",
            passwordMd5 = computeLegacyPasswordMd5("pwB"),
            lastLoginAt = 500L,
        )
    }
}
