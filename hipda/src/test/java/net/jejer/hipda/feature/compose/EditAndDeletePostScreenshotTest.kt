package net.jejer.hipda.feature.compose

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ThreadNavArgs
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
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E6b 4 张专项 Roborazzi 截图基线（前缀 `editor_edit_`，供 R-E6b 目视核验，既有快照严格 0 漂移）：
 * 1. `editor_edit_phone_w411_restore_light`：单栏 411dp 浅色，编辑首楼含「还原原文」按钮、草稿恢复横条、分类/标题与可视化缩略图块；
 * 2. `editor_edit_tablet_w1280_rightpane_dark`：平板 1280dp 深色三栏右栏内展开编辑态含「还原原文」（玻璃预算 ≤ 3）；
 * 3. `editor_edit_delete_dialog_w411_light`：单栏 411dp 浅色删除自己楼层二次确认框（`删除这条回复？` / `删除后无法恢复。` / `取消` / `删除`）；
 * 4. `editor_edit_delete_dialog_w749_dark`：749dp 紧凑三栏深色删除确认框（玻璃预算 ≤ 3）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class EditAndDeletePostScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_edit_phone_w411_restore_light() {
        val pid = "53529011"
        val editKey = DraftKey.edit(pid)
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                editKey.value to DraftEntity(
                    key = editKey.value,
                    subject = "编辑首楼示例：修改后的清晰标题",
                    typeId = "56",
                    content = "已从上次编辑草稿自动恢复，顶栏展示还原原文按钮，正文图片以缩略图块呈现。\n[attachimg]9011[/attachimg]\n补充第二段说明文字。",
                    attachments = listOf("9011", "9012"),
                    updatedAt = 2_000L,
                ),
            ),
        ).apply {
            prePostHandler = {
                PrePostResult.Success(
                    defaultSamplePrePostInfo(
                        typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery", "19" to "站务"),
                        typeId = "19",
                    ).copy(
                        subject = "原始线上标题文本",
                        text = "原始线上正文内容。\n[attachimg]9011[/attachimg]",
                        existingImages = listOf("9011"),
                    ),
                )
            }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2607001", pid = pid, page = 1, isFirstFloor = true, floorNumber = 1, author = "linus_hi"))
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                FullEditorScreen(viewModel = vm, onOpenLogin = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()
        composeRule.onNodeWithText("已恢复上次草稿").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot("editor_edit_phone_w411_restore_light")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun editor_edit_tablet_w1280_rightpane_dark() {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(themeMode = ThemeMode.DARK, themeSeed = HiColorPresets.Brand.id),
            richSampleContent = true,
        )
        val detailRepo = ScreenshotOwnFloorDetailRepository(ownUid = "42", ownAuthor = "HiUser")
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(
                    defaultSamplePrePostInfo(typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery"), typeId = "56")
                        .copy(subject = "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验", text = "线上原楼层正文内容。"),
                )
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = buildOwner(harness, detailRepo, postRepo, editorVm)

        composeRule.setContent {
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
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

        composeRule.onNodeWithText("折腾折叠屏客户端", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在 749dp 紧凑三栏下", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            editorVm.onTextBlockChanged(editorVm.uiState.value.body.firstTextBlockId, "平板横屏右栏内直接编辑已发表楼层，内容变更后顶栏呈现还原原文按钮。", 0, 0)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(3)
        composeRule.onRoot().captureHiSnapshot("editor_edit_tablet_w1280_rightpane_dark")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_edit_delete_dialog_w411_light() {
        val ownAccount = SavedAccount("1001", "linus_hi", "0".repeat(32), lastLoginAt = 100L)
        val authRepo = FakeQuickReplyAuthRepository(ownAccount)
        val detailRepo = ScreenshotOwnFloorDetailRepository(ownUid = "1001", ownAuthor = "linus_hi")
        val postRepo = FakePostRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleComposeGateway(), authRepo, postRepository = postRepo)
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        composeRule.setContent {
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "1001", title = "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在 749dp 紧凑三栏下", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()
        composeRule.onNodeWithText("删除后无法恢复。").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot("editor_edit_delete_dialog_w411_light")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun editor_edit_delete_dialog_w749_dark() {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(themeMode = ThemeMode.DARK, themeSeed = HiColorPresets.Brand.id),
            richSampleContent = true,
        )
        val detailRepo = ScreenshotOwnFloorDetailRepository(ownUid = "42", ownAuthor = "HiUser")
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = buildOwner(harness, detailRepo, postRepo, editorVm)

        composeRule.setContent {
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
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

        composeRule.onNodeWithText("折腾折叠屏客户端", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("在 749dp 紧凑三栏下", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(3)
        composeRule.onRoot().captureHiSnapshot("editor_edit_delete_dialog_w749_dark")
    }

    private fun buildOwner(
        harness: MultiPaneTestHarness,
        detailRepo: ThreadDetailRepository,
        postRepo: FakePostRepository,
        editorVm: FullEditorViewModel,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(detailRepo, harness.gateway, harness.authRepo, postRepository = postRepo) as T
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

    private class ScreenshotOwnFloorDetailRepository(
        private val ownUid: String,
        private val ownAuthor: String,
    ) : ThreadDetailRepository {
        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val floors = listOf(
                PostFloor(
                    postId = "53529011", floor = 1, page = 1, uid = ownUid, author = ownAuthor, avatarUrl = null,
                    timePost = "2026-11-26 10:05", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("在 749dp 紧凑三栏下，左栏固定 64dp 图标轨，中栏 274dp 紧凑列表，右栏保留 411dp 正文阅读宽度。")))),
                ),
                PostFloor(
                    postId = "53529012", floor = 2, page = 1, uid = "1002", author = "geek_reader", avatarUrl = null,
                    timePost = "2026-11-26 10:14", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("长按本人楼层可直接发起编辑或不可撤销的二次确认删除。")))),
                ),
            )
            return ThreadDetailLoadResult.Success(
                ThreadDetailPage(
                    tid = tid, fid = 2, title = "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验", page = 1, lastPage = 2,
                    floors = floors, postsPerPageEstimate = 20,
                    meta = DiscuzPageMeta(DiscuzSessionInfo(true, ownUid, ownAuthor), "fh_shot", null, PromptCounts()),
                    hasPostList = true,
                ),
                filter, forumName, ownUid,
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "1001", 1)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = floorsFlow.map { l -> l.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }
}
