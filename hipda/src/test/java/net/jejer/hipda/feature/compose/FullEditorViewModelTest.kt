package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.ImageUploadError
import net.jejer.hipda.data.post.ImageUploadInput
import net.jejer.hipda.data.post.ImageUploadProgress
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.ui.shell.ShellHingeBoundsDp
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E6 单元测试：
 * - `EditorBodyDocument` 双射解析/序列化、光标插图、删图合段、安全回删、0 `[attachimg]` 暴露与 URI scheme 白名单反例；
 * - `FullEditorViewModel` 发新帖 R-D1 版块必选与分类、草稿恢复与 R-D6 S2 清空/删空必调 `deleteDraft`（撤回即红）；
 * - 快速回复 0 丢失交接、内联校验文案、0ms 提交/冷却/失败保输入；
 * - §4.2 多栏投放纯函数矩阵与 `FullEditorSavedStateSaver` 进程重建往返。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FullEditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun editorBodyDocument_roundTripsAttachImgTags_insertsAtCursor_andNeverExposesRawTags() {
        val doc0 = EditorBodyDocument.parse("前文内容后文", selectionStart = 4, selectionEnd = 4)
        assertEquals(1, doc0.blocks.size)
        assertEquals(4, doc0.activeSelectionStart)
        assertEquals(4, doc0.activeSelectionEnd)

        val doc1 = doc0.insertImageAtCursor("1721001")
        assertEquals(3, doc1.blocks.size)
        assertEquals("前文内容", (doc1.blocks[0] as EditorBodyBlock.Text).text)
        assertEquals("1721001", (doc1.blocks[1] as EditorBodyBlock.Image).imageId)
        assertEquals("后文", (doc1.blocks[2] as EditorBodyBlock.Text).text)
        assertEquals(setOf("1721001"), doc1.insertedImageIds())
        assertEquals(6, doc1.plainTextTrimmedCodePoints())

        val serialized = doc1.serialize()
        assertEquals("前文内容\n[attachimg]1721001[/attachimg]\n后文", serialized)

        val reparsed = EditorBodyDocument.parse(serialized)
        assertEquals(3, reparsed.blocks.size)
        assertEquals("前文内容", (reparsed.blocks[0] as EditorBodyBlock.Text).text)
        assertEquals("1721001", (reparsed.blocks[1] as EditorBodyBlock.Image).imageId)
        assertEquals("后文", (reparsed.blocks[2] as EditorBodyBlock.Text).text)
        assertTrue(
            "UI 文本块严禁残留原始 [attachimg] 标签",
            reparsed.blocks.filterIsInstance<EditorBodyBlock.Text>().none { it.text.contains("[attachimg]") },
        )

        // 连续两张图片 + 段首 Backspace 删除前图合段
        val doc2 = reparsed.updateSelection(reparsed.lastTextBlockId, 0, 0).insertImageAtCursor("1721002")
        assertEquals("前文内容\n[attachimg]1721001[/attachimg]\n[attachimg]1721002[/attachimg]\n后文", doc2.serialize())
        val afterBackspace = doc2.backspaceAtCursor()
        assertEquals(setOf("1721001"), afterBackspace.insertedImageIds())
        assertEquals("前文内容\n[attachimg]1721001[/attachimg]\n后文", afterBackspace.serialize())

        // 粘贴含 [attachimg] 文本也立即透明转为缩略图块
        val pasted = EditorBodyDocument.empty().updateTextBlock("t0", "图文[attachimg]9988[/attachimg]混排", 0, 0)
        assertEquals(setOf("9988"), pasted.insertedImageIds())
        assertTrue(pasted.blocks.filterIsInstance<EditorBodyBlock.Text>().none { it.text.contains("[attachimg]") })
    }

    @Test
    fun uploadContentUriWhitelist_allowsValidContentUri_andRejectsAllDangerousSchemes() {
        assertTrue(isAllowedUploadContentUri(Uri.parse("content://media/external/images/media/42")))
        assertTrue(isAllowedUploadContentUri(Uri.parse("content://com.android.providers.media.documents/document/image%3A101")))

        val dangerous = listOf(
            null,
            Uri.parse(""),
            Uri.parse("file:///etc/passwd"),
            Uri.parse("file:///sdcard/DCIM/a.jpg"),
            Uri.parse("javascript:alert(1)"),
            Uri.parse("intent://scan/#Intent;scheme=zxing;end"),
            Uri.parse("data:image/png;base64,AAAA"),
            Uri.parse("http://example.com/a.jpg"),
            Uri.parse("https://example.com/a.jpg"),
            Uri.parse("mailto:user@example.com"),
            Uri.parse("android.resource://net.jejer.hipda/drawable/ic_launcher"),
            Uri.parse("content:///missing_authority"),
            Uri.parse("content://media/path with space"),
        )
        for (bad in dangerous) {
            assertFalse("不安全或非法 URI 必须被拦截: $bad", isAllowedUploadContentUri(bad))
        }
    }

    @Test
    fun newThreadFromTodayNew_rD1_requiresPositiveFidBeforeDraftAndPrePost() = runTest(testDispatcher) {
        val draftFid6 = DraftEntity(DraftKey.newThread("6").value, subject = "旧草稿标题六字以上", typeId = "12", content = "这是之前保存的交易区草稿正文内容", updatedAt = 1_000L)
        val postRepo = FakePostRepository(initialDrafts = mapOf(draftFid6.key to draftFid6)).apply {
            prePostHandler = { req ->
                val fid = (req as PrePostRequest.NewThread).fid
                PrePostResult.Success(
                    defaultSamplePrePostInfo(
                        typeOptions = if (fid == 6) linkedMapOf("0" to "分类", "12" to "出二手", "13" to "求购") else emptyMap(),
                        typeId = "0",
                    ),
                )
            }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }

        // 从今日新帖打开（initialFid = null）：未选版块，0 次 PrePost，0 次草稿读写
        vm.open(FullEditorRequest.NewThread(initialFid = null))
        advanceUntilIdle()
        assertNull("R-D1：今日新帖打开时版块必须未选中", vm.uiState.value.selectedFid)
        assertTrue("R-D1：未选版块前不得发起 fetchPrePost", postRepo.prePostRequests.isEmpty())

        // 直接点发送 -> 内联校验「请选择版块」
        vm.submit()
        assertEquals(FullEditorValidationError.SelectForum, vm.uiState.value.validationError)
        assertTrue(postRepo.submitRequests.isEmpty())

        // 未选版块时退出 -> 不得写入任何非法草稿
        vm.onSubjectChanged("临时输入未选版块")
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("R-D1：未选版块退出不得写草稿", postRepo.saveDraftCalls.isEmpty())

        // 重新打开并选择正整数版块 fid=6 -> 立即加载 new:6 草稿并预取 PrePost 分类
        vm.open(FullEditorRequest.NewThread(initialFid = null))
        advanceUntilIdle()
        vm.selectForum(6)
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.selectedFid)
        assertEquals(1, postRepo.prePostRequests.size)
        assertEquals(PrePostRequest.NewThread(6), postRepo.prePostRequests.single())
        assertEquals("旧草稿标题六字以上", vm.uiState.value.subject)
        assertEquals("12", vm.uiState.value.selectedTypeId)
        assertTrue(vm.uiState.value.showDraftRestoredBanner)
        assertTrue(vm.uiState.value.hasSelectableCategories)
    }

    /**
     * 闭合 R-D6 S2（撤回即红）：
     * 1. 恢复已有草稿后手动删空内容退出 -> 必须显式调用 `deleteDraft`，下次重开绝不复活；
     * 2. 恢复已有草稿后点横条「清空」-> 必须显式调用 `deleteDraft`；
     * 3. 快速回复交接带入已有 `reply:<tid>` 草稿后手动删空退出 -> 同样必须显式调用 `deleteDraft`。
     */
    @Test
    fun rD6S2_clearingOrEmptyingRestoredDraft_explicitlyCallsDeleteDraftAndNeverResurrects() = runTest(testDispatcher) {
        val newKey = DraftKey.newThread("2")
        val replyKey = DraftKey.reply("2608001")
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                newKey.value to DraftEntity(newKey.value, subject = "老草稿标题测试", content = "老草稿正文内容测试", updatedAt = 1_000L),
                replyKey.value to DraftEntity(replyKey.value, content = "快速回复遗留草稿", updatedAt = 1_000L),
            ),
        )
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        val events = mutableListOf<FullEditorEvent>()
        val collectJob = launch { vm.events.collect { events += it } }

        // 1. 发新帖恢复旧草稿 -> 手动删空标题与正文 -> 退出必须调用 deleteDraft(new:2) 且不弹已保存草稿
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hadPersistedDraft)
        vm.onSubjectChanged("")
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "", 0, 0)
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("R-D6 S2：手动删空旧草稿退出必须显式调用 deleteDraft", newKey in postRepo.deleteDraftCalls)
        assertNull("删除后草稿表内不得残留 new:2", postRepo.draftFor(newKey))
        assertTrue("清空旧草稿退出不得弹已保存草稿", events.none { it is FullEditorEvent.DraftSaved })

        // 重开验证绝不复活
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.subject)
        assertTrue(vm.uiState.value.body.isVisuallyEmpty())
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
        vm.requestClose()
        advanceUntilIdle()

        // 2. 点横条「清空」同样显式 deleteDraft
        postRepo.saveDraft(newKey, subject = "第二份草稿标题", content = "第二份草稿正文内容")
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showDraftRestoredBanner)
        vm.clearRestoredDraft()
        advanceUntilIdle()
        assertNull(postRepo.draftFor(newKey))
        assertFalse(vm.uiState.value.hadPersistedDraft)
        vm.requestClose()
        advanceUntilIdle()

        // 3. 快速回复交接存在 reply:2608001 持久化草稿 -> 手动删空退出必须显式调用 deleteDraft(reply:2608001)
        vm.open(
            FullEditorRequest.fromHandoff(
                FullEditorHandoff(tid = "2608001", fid = 2, text = "快速回复遗留草稿", selectionStart = 8, selectionEnd = 8),
            ),
        )
        advanceUntilIdle()
        assertTrue("交接时应侦测到已有 reply 草稿以守住 R-D6 S2", vm.uiState.value.hadPersistedDraft)
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "   ", 0, 0)
        vm.requestClose()
        advanceUntilIdle()
        assertTrue("R-D6 S2：回复交接删空旧草稿退出必须显式调用 deleteDraft", replyKey in postRepo.deleteDraftCalls)
        assertNull(postRepo.draftFor(replyKey))

        collectJob.cancel()
    }

    @Test
    fun validationSubmitCooldownAndFailurePreservation_matchCopyAndDesignSpec() = runTest(testDispatcher) {
        val nowMs = { 10_000L + testScheduler.currentTime }
        val submitGate = CompletableDeferred<PostSubmitResult>()
        val postRepo = FakePostRepository(nowMs = nowMs).apply {
            prePostHandler = {
                PrePostResult.Success(
                    defaultSamplePrePostInfo(
                        typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery"),
                        typeId = "0",
                    ),
                )
            }
            submitHandler = { submitGate.await() }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository(), nowMs)

        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()

        // 依次触发：请选择分类 -> 请输入标题 -> 标题至少 6 个字 -> 内容至少 6 个字
        vm.submit()
        assertEquals(FullEditorValidationError.SelectCategory, vm.uiState.value.validationError)

        vm.selectCategory("56")
        vm.submit()
        assertEquals(FullEditorValidationError.EnterTitle, vm.uiState.value.validationError)

        vm.onSubjectChanged("五字短标题")
        vm.submit()
        assertEquals(FullEditorValidationError.TitleTooShort, vm.uiState.value.validationError)

        vm.onSubjectChanged("满六个字的完整标题")
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "短正文", 3, 3)
        vm.submit()
        assertEquals(FullEditorValidationError.ContentTooShort, vm.uiState.value.validationError)

        // 合法输入 -> 0ms 进入 isSubmitting，失败留原地保输入
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "这是一段超过六个字的完整帖子正文内容", 18, 18)
        vm.submit()
        assertTrue("点击发送必须 0ms 进入提交中状态", vm.uiState.value.isSubmitting)

        submitGate.complete(PostSubmitResult.NetworkError("SocketTimeoutException"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSubmitting)
        assertTrue(vm.uiState.value.isOpen)
        assertEquals("满六个字的完整标题", vm.uiState.value.subject)
        assertEquals("这是一段超过六个字的完整帖子正文内容", vm.uiState.value.body.serialize())
        assertNotNull(vm.uiState.value.inlineError)

        // 服务器返回冷却 -> 进入倒计时，期间输入不丢失且仍可编辑；18s 倒计时归零后恢复可提交
        postRepo.submitHandler = { PostSubmitResult.CooldownActive(18) }
        vm.submit()
        runCurrent()
        assertEquals(18, vm.uiState.value.cooldownSeconds)
        assertEquals(18, vm.uiState.value.inlineError?.cooldownMessageSeconds)
        vm.onSubjectChanged("冷却期间继续完善标题内容")
        assertEquals("冷却期间继续完善标题内容", vm.uiState.value.subject)
        advanceTimeBy(18_000L)
        runCurrent()
        assertEquals(0, vm.uiState.value.cooldownSeconds)
    }

    @Test
    fun placementRulesAndSavedStateSaver_matchSection42AndPreserveOpenEditorAcrossProcessDeath() {
        // 单栏 411×891 -> FullScreen
        assertEquals(FullEditorPlacement.FullScreen, resolveFullEditorPlacement(calculateShellPaneLayout(411f, 891f), 891f))
        // 矮屏 891×411 -> FullScreen
        assertEquals(FullEditorPlacement.FullScreen, resolveFullEditorPlacement(calculateShellPaneLayout(891f, 411f), 411f))
        // 2 栏 640×1024 -> CenteredModal, 宽 min(640-48, 680)=592dp
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(calculateShellPaneLayout(640f, 1024f), 1024f))
        assertEquals(592f, calculateCenteredModalWidthDp(640f).value, 0.1f)
        // 物理铰链双栏 749×559 -> CenteredModal
        val hinge = calculateShellPaneLayout(749f, 559f, hingeBoundsDp = ShellHingeBoundsDp(371f, 378f))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(hinge, 559f))
        // 三栏 749×559（右栏 411 < 560）-> CenteredModal, 宽 680dp
        val fold8 = calculateShellPaneLayout(749f, 559f)
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(fold8, 559f, fold8.rightPaneWidthDp.toFloat()))
        assertEquals(680f, calculateCenteredModalWidthDp(749f).value, 0.1f)
        // 三栏 933×696（右栏 448 < 560）-> CenteredModal, 宽 680dp
        val w933 = calculateShellPaneLayout(933f, 696f)
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(w933, 696f, w933.rightPaneWidthDp.toFloat()))
        // 平板 1280×800（右栏 680 ≥ 560）-> RightPane
        val w1280 = calculateShellPaneLayout(1280f, 800f)
        assertEquals(FullEditorPlacement.RightPane, resolveFullEditorPlacement(w1280, 800f, w1280.rightPaneWidthDp.toFloat()))

        // FullEditorSavedStateSaver 进程恢复往返
        val original = FullEditorSavedState(
            isOpen = true,
            mode = FullEditorMode.QuotePost("2609001", 2, "502", "alice", 2, "linus_hi"),
            selectedFid = 2,
            selectedTypeId = "56",
            subject = "",
            serializedBody = "第一段\n[attachimg]7788[/attachimg]\n第二段",
            selectionStart = 2,
            selectionEnd = 3,
            uploadedImageIds = listOf("7788", "7789"),
            hadPersistedDraft = true,
            showDraftRestoredBanner = true,
        )
        val allowAll = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(FullEditorSavedStateSaver) { allowAll.save(original) }
        val restored = FullEditorSavedStateSaver.restore(requireNotNull(saved))
        assertEquals(original, restored)
    }

    @Test
    fun activeOrFailedUploads_blockSubmitInPlaceWithoutLosingInput_untilResolved() = runTest(testDispatcher) {
        val stream1 = MutableSharedFlow<ImageUploadProgress>(extraBufferCapacity = 4)
        val stream2 = MutableSharedFlow<ImageUploadProgress>(extraBufferCapacity = 4)
        val uri1 = Uri.parse("content://net.jejer.hipda.test/media/p1.jpg")
        val uri2 = Uri.parse("content://net.jejer.hipda.test/media/p2.jpg")
        val uploadRepo = FakeImageUploadRepository { input, _, _, _ ->
            val u = (input as ImageUploadInput.ContentUri).uri
            if (u == uri1) stream1 else stream2
        }
        val postRepo = FakePostRepository()
        val vm = FullEditorViewModel(postRepo, uploadRepo, FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }

        vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff(tid = "2608009", fid = 2, text = "带图回复测试文本", selectionStart = 8, selectionEnd = 8)))
        advanceUntilIdle()

        vm.onPickImages(listOf(uri1, uri2))
        advanceUntilIdle()
        assertEquals(1 to 2, vm.uiState.value.uploadSummaryProgress)
        // 上传中点发送 -> 原地提示且不丢输入
        vm.submit()
        assertTrue(postRepo.submitRequests.isEmpty())
        assertEquals(net.jejer.hipda.R.string.compose_upload_in_progress_hint, vm.uiState.value.inlineError?.uiError?.messageRes)
        assertEquals("带图回复测试文本", vm.uiState.value.body.serialize())

        // 第一张成功、第二张失败 -> 汇总消失但仍因失败图拦截发送
        stream1.emit(ImageUploadProgress.Succeeded("9001", 1024L, true))
        stream2.emit(ImageUploadProgress.Failed(ImageUploadError.NetworkError("timeout")))
        advanceUntilIdle()
        assertNull(vm.uiState.value.uploadSummaryProgress)
        vm.submit()
        assertTrue(postRepo.submitRequests.isEmpty())
        assertEquals(net.jejer.hipda.R.string.compose_upload_has_failed_hint, vm.uiState.value.inlineError?.uiError?.messageRes)

        // 移除失败项后即可正常发送，并带上第一张图片附件
        val failedId = vm.uiState.value.uploads.first { it.isFailed }.localId
        vm.removeUpload(failedId)
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, postRepo.submitRequests.size)
        assertEquals(listOf("9001"), postRepo.submitRequests.single().newAttachments)
    }

    /**
     * B1 ① 撤回即红：
     * - 已打开状态下收到同一草稿键的重复 `open()` 请求（如 1280dp 右栏编辑时误点中栏「发新帖」）：不重置标题/正文/已传图，仅递增聚焦信号；
     * - 收到不同草稿键请求：先按退出规则保存旧会话草稿并发送「已保存草稿」事件（或空旧草稿调 `deleteDraft`），再切到新会话并加载其草稿。
     */
    @Test
    fun b1_openWhileOpen_sameDraftKeyPreservesAndFocuses_differentDraftKeySavesAndSwitches() = runTest(testDispatcher) {
        val keyFid2 = DraftKey.newThread("2")
        val keyFid6 = DraftKey.newThread("6")
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                keyFid6.value to DraftEntity(keyFid6.value, subject = "交易区已有草稿标题", content = "交易区旧草稿正文内容", updatedAt = 1_000L),
            ),
        )
        val uploadRepo = FakeImageUploadRepository()
        val vm = FullEditorViewModel(postRepo, uploadRepo, FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        val events = mutableListOf<FullEditorEvent>()
        val job = launch { vm.events.collect { events += it } }

        // 1. 打开 fid=2 发新帖，填写标题、正文并上传一张图片
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        vm.onSubjectChanged("在编 Discovery 标题")
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "在编 Discovery 正文内容", 14, 14)
        vm.onPickImages(listOf(Uri.parse("content://net.jejer.hipda.test/media/stay1.jpg")))
        advanceUntilIdle()
        val focusBefore = vm.uiState.value.focusRequestVersion
        val sessionBefore = vm.uiState.value.sessionId
        assertEquals(listOf("1721001"), vm.uiState.value.uploadedImageIds)

        // 2. 同一草稿键再次请求 open(NewThread(2)) -> 不重置任何内容，仅触发重新聚焦
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        assertEquals("同草稿键不得重建会话", sessionBefore, vm.uiState.value.sessionId)
        assertTrue("同草稿键应触发重新聚焦现有编辑器", vm.uiState.value.focusRequestVersion > focusBefore)
        assertEquals("在编 Discovery 标题", vm.uiState.value.subject)
        assertEquals("在编 Discovery 正文内容", vm.uiState.value.body.serialize())
        assertEquals(listOf("1721001"), vm.uiState.value.uploadedImageIds)
        assertTrue("同草稿键幂等不得保存/弹草稿", events.isEmpty())

        // 3. 切换到不同草稿键 NewThread(6) -> 先存 new:2 草稿并弹「已保存草稿」，再载入 new:6 既有草稿
        vm.open(FullEditorRequest.NewThread(initialFid = 6))
        advanceUntilIdle()
        val savedFid2 = postRepo.draftFor(keyFid2)
        assertNotNull("跨会话切换前必须先保存前序草稿 new:2", savedFid2)
        assertEquals("在编 Discovery 标题", savedFid2?.subject)
        assertEquals("在编 Discovery 正文内容", savedFid2?.content)
        assertEquals(listOf("1721001"), savedFid2?.attachments)
        assertTrue("跨会话保存前序非空草稿必须发出 DraftSaved 事件", events.any { it is FullEditorEvent.DraftSaved && it.draftKey == keyFid2 })
        assertEquals(6, vm.uiState.value.selectedFid)
        assertEquals("交易区已有草稿标题", vm.uiState.value.subject)
        assertEquals("交易区旧草稿正文内容", vm.uiState.value.body.serialize())
        assertTrue(vm.uiState.value.showDraftRestoredBanner)

        // 4. 在 fid=6 删空已恢复草稿后切回 fid=2 -> 必须按 R-D6 S2 删除 new:6 且恢复刚保存的 new:2
        vm.onSubjectChanged("")
        vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "", 0, 0)
        vm.open(FullEditorRequest.NewThread(initialFid = 2))
        advanceUntilIdle()
        assertTrue("切离已删空的旧草稿会话必须调用 deleteDraft(new:6)", keyFid6 in postRepo.deleteDraftCalls)
        assertNull(postRepo.draftFor(keyFid6))
        assertEquals("在编 Discovery 标题", vm.uiState.value.subject)
        assertEquals("在编 Discovery 正文内容", vm.uiState.value.body.serialize())
        assertEquals(listOf("1721001"), vm.uiState.value.uploadedImageIds)

        job.cancel()
    }

    /**
     * B1 ② 撤回即红：
     * 在 `QuickReplySheet` 恢复既有 `reply:<tid>` 草稿后手动删空为 `""`，再点「展开全屏 / 插入图片」交接完整编辑器：
     * 1. `handoff.text == ""` 是权威内容，完整编辑器绝不得从数据库重新覆盖/复活旧草稿；
     * 2. `hadPersistedDraft` 随 `handoff` 透传，随后退出空编辑器必须按 `R-D6 S2` 显式调用 `deleteDraft(reply:<tid>)`。
     */
    @Test
    fun b1_quickReplyEmptyHandoff_neverResurrectsOldDraft_andDeletesOnClosePerRD6S2() = runTest(testDispatcher) {
        val tid = "2608099"
        val replyKey = DraftKey.reply(tid)
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                replyKey.value to DraftEntity(replyKey.value, content = "上次未发出的旧草稿", attachments = listOf("7011"), updatedAt = 1_000L),
            ),
        )
        val authRepo = FakeQuickReplyAuthRepository()
        val qrVm = QuickReplyViewModel(postRepo, authRepo) { 10_000L }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        val editorEvents = mutableListOf<FullEditorEvent>()
        val collectJob = launch { editorVm.events.collect { editorEvents += it } }

        // 1. 快速回复恢复旧草稿，用户手动删空为 "" 后点击「插入图片」交接
        qrVm.open(QuickReplyTarget(tid = tid, fid = 2))
        advanceUntilIdle()
        assertEquals("上次未发出的旧草稿", qrVm.uiState.value.text)
        assertTrue(qrVm.uiState.value.hadPersistedDraft)

        qrVm.onTextChanged("")
        val handoff = qrVm.onHandoffToFullEditor(text = "", selectionStart = 0, selectionEnd = 0, openImagePickerImmediately = true)
        assertEquals("", handoff.text)
        assertTrue("handoff 必须透传 hadPersistedDraft=true", handoff.hadPersistedDraft)
        assertFalse("空正文交接不应展示已恢复草稿横条", handoff.showDraftRestoredBanner)

        // 2. 完整编辑器承接：正文必须保持空串（绝不复活旧草稿），也不复活旧附件
        editorVm.open(FullEditorRequest.fromHandoff(handoff))
        advanceUntilIdle()
        assertEquals("快速回复删空交接后绝不得复活旧草稿正文", "", editorVm.uiState.value.body.serialize())
        assertTrue("空正文交接不得复活旧草稿附件", editorVm.uiState.value.uploads.isEmpty())
        assertTrue("必须保留 hadPersistedDraft=true 以守住 R-D6 S2", editorVm.uiState.value.hadPersistedDraft)
        assertFalse(editorVm.uiState.value.showDraftRestoredBanner)

        // 3. 退出空编辑器 -> 按 R-D6 S2 显式 deleteDraft 且不弹「已保存草稿」
        editorVm.requestClose()
        advanceUntilIdle()
        assertTrue("空退时必须显式删除被快速回复删空的旧草稿", replyKey in postRepo.deleteDraftCalls)
        assertNull("旧草稿必须已从草稿表彻底清除", postRepo.draftFor(replyKey))
        assertTrue(editorEvents.none { it is FullEditorEvent.DraftSaved })

        // 4. 再次重开确认旧草稿彻底消失、绝不复活
        qrVm.open(QuickReplyTarget(tid = tid, fid = 2))
        advanceUntilIdle()
        assertEquals("", qrVm.uiState.value.text)
        assertFalse(qrVm.uiState.value.hadPersistedDraft)

        collectJob.cancel()
    }
}
