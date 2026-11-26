package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.R
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
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.post.TestImageFactory
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E6b 编辑楼层核心单测与全量 `MockWebServer` 离线集成测试（0 访问真实站点）：
 * 1. `MockWebServer` + 真实 `DefaultPostRepository` + `DefaultImageUploadRepository` 验证：
 *    `PrePostRequest.EditPost` 拉取原文 → `[attachimg]` 反向解析不暴露源码 → `还原原文` 显隐与独立令牌撤销（含连续两次还原）→
 *    编辑免 30s 冷却且不附小尾巴 → `deletedAttachments`(`attachdel[]`) + 新图上传(`attachnew[][description]`) → 提交成功清 `edit:<pid>` 草稿；
 * 2. `edit:<pid>` 草稿全生命周期与 `R-D6 S2`（撤回即红）：未改动 0 保存 0 提示、改动保存/放弃、恢复旧草稿后手动删空/点清空/还原原文退出显式 `deleteDraft` 绝不复活；
 * 3. 原文加载失败重试、0ms 提交中与失败留页保输入、`FullEditorSavedStateSaver` 编辑态进程重建往返。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditPostMockWebServerAndDraftTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var virtualClockMs: AtomicLong
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        workDir = tempFolder.newFolder("e6b_edit_work")
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
        Dispatchers.resetMain()
    }

    private data class RealStack(
        val vm: FullEditorViewModel,
        val postRepo: DefaultPostRepository,
        val draftDao: InMemoryDraftDao,
    )

    private fun createRealEditStack(tailEnabled: Boolean = true): RealStack {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credentialsStore = PostTestCredentialsStore()
        val settingsRepo = PostTestSettingsRepository(
            AppSettings(
                forumServer = server.url("").toString().trimEnd('/'),
                tailEnabled = tailEnabled,
                tailText = "绝不应附带的客户端小尾巴",
                tailUrl = "https://example.com/tail",
            ),
        )
        runBlocking {
            val acc = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
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
        val compressor = ImageCompressor(context, { workDir }, Dispatchers.IO)
        val uploadRepo = DefaultImageUploadRepository(primaryGw, settingsRepo, authRepo, compressor, { virtualClockMs.get() })
        val vm = FullEditorViewModel(postRepo, uploadRepo, authRepo, settingsRepo, { virtualClockMs.get() })
        return RealStack(vm, postRepo, draftDao)
    }

    @Test
    fun mockWebServer_editPostFullFlow_restoreUndoTokens_cooldownBypass_noTail_deletedAndNewAttachments_clearsDraft() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val newPhotoBytes = TestImageFactory.writeSolidPng(File(tempFolder.root, "edit_new.png"), 120, 90).readBytes()
        val newUri = Uri.parse("content://net.jejer.hipda.test/media/edit_new.png")
        shadowOf(context.contentResolver).registerInputStream(newUri, ByteArrayInputStream(newPhotoBytes))

        val editPostTargets = CopyOnWriteArrayList<String>()
        val editPostBodies = CopyOnWriteArrayList<String>()
        val editSubmitAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("post.php?action=reply&tid=2607001") && request.method == "GET" ->
                        gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "reply_cd_fh"))

                    target.contains("post.php?action=reply&tid=2607001&replysubmit=yes") && request.method == "POST" ->
                        MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=2607001&page=1").build()

                    target.contains("post.php?action=edit&fid=2&tid=2607001&pid=53529001&page=1") && request.method == "GET" ->
                        gbkHtmlResponse(
                            PostTestFixtures.prePostHtml(
                                formhash = "edit_fh_01",
                                uid = "10000",
                                hash = "edit_upload_hash_01",
                                subject = "原始首楼完整标题",
                                textarea = "原始第一段正文\n[attachimg]9001[/attachimg]\n原始第二段正文",
                                isDeletable = true,
                                typeOptions = listOf("0" to "分类", "56" to "Discovery", "19" to "站务"),
                                selectedTypeId = "56",
                                existingImages = listOf("9001", "9002"),
                            ),
                        )

                    target.contains("misc.php?action=swfupload") ->
                        MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|9009|1").build()

                    target.contains("post.php?action=edit") && target.contains("editsubmit=yes") && request.method == "POST" -> {
                        editPostTargets += target
                        editPostBodies += request.body!!.utf8()
                        if (editSubmitAttempts.incrementAndGet() == 1) {
                            // 第一次提交模拟站点错误，验证失败留页保输入且不删草稿
                            gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser", "<div class=\"alert_error\">编辑频率异常请重试</div>"))
                        } else {
                            MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=2607001&page=1").build()
                        }
                    }

                    target.contains("viewthread.php?tid=2607001") ->
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser", "<div id=\"postlist\"></div>"))

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val stack = createRealEditStack(tailEnabled = true)
        val editKey = DraftKey.edit("53529001")

        withTimeout(15.seconds) {
            // 1. 先发起一次普通回复启动真实 30 秒冷却，并验证编辑模式彻底免受冷却限制
            val replyRes = stack.postRepo.submitPost(PostSubmitRequest.ReplyThread(tid = "2607001", content = "先发回复触发30秒冷却"))
            assertTrue(replyRes is PostSubmitResult.Success)
            val cooldownDeadlineBeforeEdit = stack.postRepo.cooldownUntilMs.value
            assertTrue("前提：30 秒冷却必须正在激活", stack.postRepo.getRemainingCooldownSeconds() in 29..30)

            val events = CopyOnWriteArrayList<FullEditorEvent>()
            val eventJob = scope.launch { stack.vm.events.collect { events += it } }

            // 2. 打开编辑态：拉取线上原文，反向解析 [attachimg]9001[/attachimg]（文本块 0 源码标签）
            stack.vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = "53529001", page = 1, isFirstFloor = true, floorNumber = 1, author = "HiUser"))
            awaitCondition { stack.vm.uiState.value.editOriginal != null }

            val loaded = stack.vm.uiState.value
            assertEquals("原始首楼完整标题", loaded.subject)
            assertEquals("56", loaded.selectedTypeId)
            assertEquals(setOf("9001"), loaded.insertedImageIds)
            assertEquals(listOf("9001", "9002"), loaded.uploadedImageIds)
            assertTrue("编辑正文文本块严禁暴露 [attachimg]", loaded.body.blocks.filterIsInstance<EditorBodyBlock.Text>().none { it.text.contains("[attachimg]") })
            assertFalse("与线上原文一致时「还原原文」必须彻底隐藏", loaded.canRestoreOriginal)
            assertEquals("编辑态有效冷却必须为 0（免 30s 冷却）", 0, loaded.effectiveCooldownSeconds)
            assertTrue("冷却激活时编辑发送按钮仍必须可用", loaded.canTapSend)

            // 3. 依次偏离标题/分类/正文/图片 -> 「还原原文」出现；连续两次还原原文验证独立令牌撤销
            stack.vm.onSubjectChanged("编辑第一版标题内容")
            assertTrue("标题改动后「还原原文」必须出现", stack.vm.uiState.value.canRestoreOriginal)
            stack.vm.selectCategory("19")
            stack.vm.onTextBlockChanged(stack.vm.uiState.value.body.firstTextBlockId, "编辑第一版正文", 7, 7)

            stack.vm.restoreOriginal()
            val token1 = stack.vm.uiState.value.restoreSnackbarEvent!!.tokenId
            assertEquals("原始首楼完整标题", stack.vm.uiState.value.subject)
            assertEquals("56", stack.vm.uiState.value.selectedTypeId)
            assertFalse("还原原文后应立即隐藏「还原原文」", stack.vm.uiState.value.canRestoreOriginal)

            // 二次修改后再点「还原原文」生成 token2，立即作废 token1
            stack.vm.onSubjectChanged("编辑第二版最终标题")
            stack.vm.selectCategory("19")
            val img9001LocalId = stack.vm.uiState.value.uploads.first { it.imageIdOrNull == "9001" }.localId
            stack.vm.removeUpload(img9001LocalId)
            assertEquals(listOf("9001"), stack.vm.uiState.value.deletedEditAttachmentIds)

            stack.vm.restoreOriginal()
            val token2 = stack.vm.uiState.value.restoreSnackbarEvent!!.tokenId
            assertTrue(token2 > token1)
            assertEquals("原始首楼完整标题", stack.vm.uiState.value.subject)
            assertEquals(listOf("9001", "9002"), stack.vm.uiState.value.uploadedImageIds)
            assertTrue(stack.vm.uiState.value.deletedEditAttachmentIds.isEmpty())

            // 旧令牌 token1 必须无效；新令牌 token2 精确恢复第二版编辑态（含已删 9001）
            stack.vm.undoRestoreOriginal(token1)
            assertEquals("旧撤销令牌不得生效", "原始首楼完整标题", stack.vm.uiState.value.subject)
            stack.vm.undoRestoreOriginal(token2)
            assertEquals("编辑第二版最终标题", stack.vm.uiState.value.subject)
            assertEquals("19", stack.vm.uiState.value.selectedTypeId)
            assertEquals(listOf("9001"), stack.vm.uiState.value.deletedEditAttachmentIds)

            // 4. 在编辑态新增上传一张图片 9009，并先存一份 edit:53529001 草稿验证提交成功后自动清理
            stack.vm.onPickImages(listOf(newUri))
            awaitCondition { "9009" in stack.vm.uiState.value.uploadedImageIds }
            assertEquals(listOf("9009"), stack.vm.uiState.value.newEditAttachmentIds)
            assertEquals(listOf("9001"), stack.vm.uiState.value.deletedEditAttachmentIds)
            stack.postRepo.saveDraft(editKey, subject = "编辑第二版最终标题", content = stack.vm.uiState.value.body.serialize(), attachments = stack.vm.uiState.value.uploadedImageIds)
            assertNotNull(stack.draftDao.observe(editKey.value).first())

            // 5. 第一次提交失败 -> 留在本页保留全部编辑内容且草稿不被误删
            stack.vm.submit()
            awaitCondition { !stack.vm.uiState.value.isSubmitting && stack.vm.uiState.value.inlineError != null }
            assertTrue("编辑失败必须留在本页", stack.vm.uiState.value.isOpen)
            assertEquals("编辑频率异常请重试", stack.vm.uiState.value.inlineError?.customMessage)
            assertEquals("编辑第二版最终标题", stack.vm.uiState.value.subject)
            assertEquals(listOf("9001"), stack.vm.uiState.value.deletedEditAttachmentIds)
            assertNotNull("失败时不得删除 edit:<pid> 草稿", stack.draftDao.observe(editKey.value).first())

            // 6. 重试提交成功 -> 删除 edit:53529001 草稿、关闭编辑器、不刷新冷却、0 小尾巴、表单精准含 attachdel[] 与 attachnew
            virtualClockMs.addAndGet(5_000L)
            stack.vm.submit()
            awaitCondition { !stack.vm.uiState.value.isOpen }

            assertNull("编辑成功后必须删除 edit:<pid> 草稿", stack.draftDao.observe(editKey.value).first())
            assertEquals("编辑成功绝不得刷新/启动 30s 冷却", cooldownDeadlineBeforeEdit, stack.postRepo.cooldownUntilMs.value)
            val postEdited = events.filterIsInstance<FullEditorEvent.PostEdited>().single()
            assertEquals("2607001", postEdited.tid)
            assertEquals("53529001", postEdited.pid)
            assertTrue(postEdited.isFirstFloor)

            val formFields = parseGbkFormFields(editPostBodies.last())
            val message = formFields.firstOrNull { it.first == "message" }?.second.orEmpty()
            val attachDel = formFields.filter { it.first == "attachdel[]" }.map { it.second }
            val attachNew = formFields.filter { it.first == "attachnew[][description]" }.map { it.second }
            assertEquals("编辑第二版最终标题", formFields.firstOrNull { it.first == "subject" }?.second)
            assertEquals("19", formFields.firstOrNull { it.first == "typeid" }?.second)
            assertEquals(listOf("9001"), attachDel)
            assertEquals(listOf("9009"), attachNew)
            assertFalse("编辑提交绝不得附加小尾巴：$message", message.contains("小尾巴"))
            assertFalse("已移除的 9001 不得残留正文：$message", message.contains("[attachimg]9001[/attachimg]"))
            assertTrue("新上传的 9009 必须附在正文：$message", message.contains("[attachimg]9009[/attachimg]"))
            eventJob.cancel()
        }
    }

    /**
     * 验收项 2（撤回即红）：`edit:<pid>` 草稿保存/放弃与 `R-D6 S2`（手动删空 / 点清空 / 还原原文后退出均显式 `deleteDraft`，重开绝不复活）。
     */
    @Test
    fun editDraftLifecycle_unmodifiedZeroSave_modifiedSaveAndDiscard_andRD6S2NeverResurrects() = runTest(StandardTestDispatcher()) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pid = "53529088"
        val editKey = DraftKey.edit(pid)
        val postRepo = FakePostRepository().apply {
            prePostHandler = { req ->
                assertEquals(PrePostRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2), req)
                PrePostResult.Success(
                    defaultSamplePrePostInfo().copy(
                        subject = "",
                        text = "线上原始楼层正文内容",
                        existingImages = listOf("8001"),
                    ),
                )
            }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        val events = mutableListOf<FullEditorEvent>()
        val collectJob = launch { vm.events.collect { events += it } }

        // 1. 未改动原文直接退出 -> 0 saveDraft，0 DraftSaved 提示
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canRestoreOriginal)
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("未改动原文退出不得写草稿", postRepo.saveDraftCalls.isEmpty())
        assertTrue("未改动原文退出不得弹已保存草稿", events.none { it is FullEditorEvent.DraftSaved })

        // 2. 改动后退出 -> 静默存 edit:<pid> 草稿并弹 DraftSaved；重开自动恢复并展示「已恢复上次草稿」横条
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "已修改待存草稿的正文内容", 12, 12)
        vm.requestClose()
        advanceUntilIdle()
        assertEquals("已修改待存草稿的正文内容", postRepo.draftFor(editKey)?.content)
        val savedEv = events.filterIsInstance<FullEditorEvent.DraftSaved>().single()
        assertEquals(editKey, savedEv.draftKey)
        assertTrue(savedEv.isReply)

        // 3. R-D6 S2 路径 A：重开恢复旧 edit:<pid> 草稿 -> 手动删空文字与图片 -> 退出必须显式 deleteDraft，重开绝不复活
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hadPersistedDraft)
        assertTrue(vm.uiState.value.showDraftRestoredBanner)
        assertEquals("已修改待存草稿的正文内容", vm.uiState.value.body.serialize())

        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "", 0, 0)
        vm.uiState.value.uploads.forEach { vm.removeUpload(it.localId) }
        events.clear()
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("R-D6 S2：恢复旧 edit 草稿后手动删空退出必须显式调用 deleteDraft", editKey in postRepo.deleteDraftCalls)
        assertNull("删空退出后草稿表不得残留 edit:<pid>", postRepo.draftFor(editKey))
        assertTrue("删空退出不得弹已保存草稿", events.none { it is FullEditorEvent.DraftSaved })

        // 重开验证加载干净线上原文，旧草稿绝不复活
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        assertEquals("线上原始楼层正文内容", vm.uiState.value.body.serialize())
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
        assertFalse(vm.uiState.value.hadPersistedDraft)
        vm.requestClose()
        advanceUntilIdle()

        // 4. R-D6 S2 路径 B：恢复旧 edit:<pid> 草稿 -> 点横条「清空」-> 显式 deleteDraft，退出再重开绝不复活
        postRepo.saveDraft(editKey, content = "第二份待清空编辑草稿", attachments = listOf("8001"))
        postRepo.deleteDraftCalls.clear()
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showDraftRestoredBanner)
        vm.clearRestoredDraft()
        advanceUntilIdle()
        assertTrue("点清空必须显式调用 deleteDraft(edit:<pid>)", editKey in postRepo.deleteDraftCalls)
        assertNull(postRepo.draftFor(editKey))
        vm.requestClose()
        advanceUntilIdle()

        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        assertEquals("线上原始楼层正文内容", vm.uiState.value.body.serialize())
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
        vm.requestClose()
        advanceUntilIdle()

        // 5. R-D6 S2 路径 C：恢复旧 edit:<pid> 草稿 -> 点「还原原文」后退出 -> 同样显式 deleteDraft 且 0 提示，重开绝不复活
        postRepo.saveDraft(editKey, content = "第三份还原后退出草稿", attachments = listOf("8001"))
        postRepo.deleteDraftCalls.clear()
        events.clear()
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        vm.restoreOriginal()
        assertFalse(vm.uiState.value.canRestoreOriginal)
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("还原原文后退出必须清理已失效的 edit:<pid> 草稿", editKey in postRepo.deleteDraftCalls)
        assertNull(postRepo.draftFor(editKey))
        assertTrue(events.none { it is FullEditorEvent.DraftSaved })

        // 6. 改动退出后点「放弃」Snackbar -> 调用 discardSavedDraft 删除草稿
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 2, isFirstFloor = false))
        advanceUntilIdle()
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "待点放弃删除的草稿正文", 11, 11)
        vm.requestClose()
        advanceUntilIdle()
        assertNotNull(postRepo.draftFor(editKey))
        vm.discardSavedDraft(editKey)
        advanceUntilIdle()
        assertNull(postRepo.draftFor(editKey))

        collectJob.cancel()
    }

    @Test
    fun editPrePostFailureRetry_zeroMsSubmit_andSavedStateSaverRoundTrip() = runTest(StandardTestDispatcher()) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val submitGate = CompletableDeferred<PostSubmitResult>()
        var fetchCalls = 0
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                fetchCalls++
                if (fetchCalls == 1) PrePostResult.NetworkError("SocketTimeoutException")
                else PrePostResult.Success(defaultSamplePrePostInfo().copy(subject = "首楼原标题六字", text = "线上原正文内容", existingImages = listOf("701")))
            }
            submitHandler = { submitGate.await() }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }

        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = "7701", page = 1, isFirstFloor = true, floorNumber = 1, author = "HiUser"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.editOriginal)
        assertEquals(R.string.common_error_network, vm.uiState.value.editLoadError?.uiError?.messageRes)
        assertFalse("原文加载失败时发送按钮不可用", vm.uiState.value.canTapSend)

        // 重试拉取成功
        vm.retryEditLoad()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.editOriginal)
        assertNull(vm.uiState.value.editLoadError)
        assertEquals("首楼原标题六字", vm.uiState.value.subject)

        // 0ms 提交中检查
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "编辑后的完整正文内容", 10, 10)
        vm.submit()
        assertTrue("编辑提交必须 0ms 切入 isSubmitting", vm.uiState.value.isSubmitting)
        submitGate.complete(PostSubmitResult.Success(tid = "2607001"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isOpen)

        // FullEditorSavedStateSaver 编辑态往返（含 editOriginal 基准）
        val savedEdit = FullEditorSavedState(
            isOpen = true,
            mode = FullEditorMode.EditPost(fid = 2, tid = "2607001", pid = "7701", page = 3, isFirstFloor = true, floorNumber = 1, author = "HiUser"),
            selectedFid = 2,
            selectedTypeId = "56",
            subject = "修改后标题六字",
            serializedBody = "修改后正文",
            selectionStart = 2,
            selectionEnd = 5,
            uploadedImageIds = listOf("702"),
            hadPersistedDraft = true,
            showDraftRestoredBanner = true,
            activeTextBlockOrdinal = 0,
            editOriginalSubject = "首楼原标题六字",
            editOriginalTypeId = "19",
            editOriginalSerializedBody = "线上原正文内容",
            editOriginalImageIds = listOf("701"),
        )
        val allowAll = SaverScope { true }
        val bundle = with(FullEditorSavedStateSaver) { allowAll.save(savedEdit) }
        assertEquals(savedEdit, FullEditorSavedStateSaver.restore(requireNotNull(bundle)))
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        while (!condition()) delay(25)
    }

    private fun parseGbkFormFields(rawBody: String): List<Pair<String, String>> {
        val gbk = Charset.forName("GBK")
        return rawBody.split('&').filter { it.isNotEmpty() }.map { pair ->
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            URLDecoder.decode(rawKey, gbk.name()) to URLDecoder.decode(rawVal, gbk.name())
        }
    }

    private class InMemoryDraftDao : DraftDao {
        private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
        override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
        override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
        override suspend fun delete(key: String) { store.value = store.value - key }
    }
}
