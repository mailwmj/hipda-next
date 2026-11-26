package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.ImageUploadError
import net.jejer.hipda.data.post.ImageUploadInput
import net.jejer.hipda.data.post.ImageUploadProgress
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
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
 * E6 完整编辑器 12 张 Roborazzi 截图基线（前缀 `editor_`）：
 * 1. 单栏 `411dp` 浅/深（发新帖含版块与分类）；
 * 2. 单栏 `411dp` 浅/深（引用回复态）；
 * 3. 单栏 `411dp` 多图上传条（压缩中 + 确定进度 + 单张失败重试 + 未插图角标 + 正文可视化缩略图块，0 `[attachimg]`）；
 * 4. 单栏 `411dp` 草稿恢复横条 + 内联校验错误态；
 * 5. 单栏 `411dp` 深色冷却倒计时 + 发送失败重试态；
 * 6. 2 栏 `640dp` 覆盖全部栏居中模态（宽 `≤ 680dp`）；
 * 7. 三栏 `749dp`（浅色）与 `933dp`（深色）右栏 `< 560dp` 全三栏居中模态；
 * 8. 平板 `1280dp` 右栏 `≥ 560dp` 右栏内展开（浅/深，玻璃预算 `≤ 3` 守卫）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class FullEditorScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_new_thread_light() = captureStandalone(
        name = "editor_phone_w411_new_thread_light",
        themeMode = HiThemeMode.LIGHT,
        configure = { vm ->
            vm.open(FullEditorRequest.NewThread(initialFid = 2))
        },
        afterLoad = { vm ->
            vm.selectCategory("56")
            vm.onSubjectChanged("聊聊折叠屏三栏客户端的完整编辑器体验")
            vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "纯色编辑器表面对比度清晰，支持自动保存草稿与多图并发上传。", 0, 0)
        },
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_new_thread_dark() = captureStandalone(
        name = "editor_phone_w411_new_thread_dark",
        themeMode = HiThemeMode.DARK,
        configure = { vm ->
            vm.open(FullEditorRequest.NewThread(initialFid = 2))
        },
        afterLoad = { vm ->
            vm.selectCategory("56")
            vm.onSubjectChanged("深色模式下完整编辑器视觉层级基线")
            vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "纯色表面零玻璃开销，底部仅保留插入图片工具项。", 0, 0)
        },
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_reply_light() = captureStandalone(
        name = "editor_phone_w411_reply_light",
        themeMode = HiThemeMode.LIGHT,
        configure = { vm ->
            vm.open(
                FullEditorRequest.fromHandoff(
                    FullEditorHandoff(
                        tid = "2607001", fid = 2,
                        text = "承接自快速回复的完整回复内容，文本、光标与引用楼层全部零丢失保留。",
                        selectionStart = 12, selectionEnd = 12,
                        quotePid = "102", quoteAuthor = "geek_reader", quoteFloor = 2, threadAuthor = "linus_hi",
                    ),
                ),
            )
        },
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_reply_dark() = captureStandalone(
        name = "editor_phone_w411_reply_dark",
        themeMode = HiThemeMode.DARK,
        configure = { vm ->
            vm.open(
                FullEditorRequest.fromHandoff(
                    FullEditorHandoff(
                        tid = "2607001", fid = 2,
                        text = "深色模式引用第 2 楼回复视图，无冗余 BBCode 快捷按钮。",
                        selectionStart = 10, selectionEnd = 10,
                        quotePid = "102", quoteAuthor = "geek_reader", quoteFloor = 2, threadAuthor = "linus_hi",
                    ),
                ),
            )
        },
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_upload_inline_light() {
        val uriInserted = Uri.parse("content://net.jejer.hipda.test/media/u1.jpg")
        val uriUninserted = Uri.parse("content://net.jejer.hipda.test/media/u2.jpg")
        val uriUploading = Uri.parse("content://net.jejer.hipda.test/media/u3.jpg")
        val uriCompressing = Uri.parse("content://net.jejer.hipda.test/media/u4.jpg")
        val uriFailed = Uri.parse("content://net.jejer.hipda.test/media/u5.jpg")
        val uploadingFlow = MutableSharedFlow<ImageUploadProgress>(replay = 1)
        val compressingFlow = MutableSharedFlow<ImageUploadProgress>(replay = 1)
        uploadingFlow.tryEmit(ImageUploadProgress.Uploading(640L, 1000L, 0.64f))
        compressingFlow.tryEmit(ImageUploadProgress.Compressing)

        val uploadRepo = FakeImageUploadRepository { input, _, _, _ ->
            when ((input as ImageUploadInput.ContentUri).uri) {
                uriInserted -> flowOf(ImageUploadProgress.Succeeded("810001", 2048L, true))
                uriUninserted -> flowOf(ImageUploadProgress.Succeeded("810002", 2048L, true))
                uriUploading -> uploadingFlow
                uriCompressing -> compressingFlow
                else -> flowOf(ImageUploadProgress.Failed(ImageUploadError.NetworkError("timeout")))
            }
        }
        captureStandalone(
            name = "editor_phone_w411_upload_inline_light",
            themeMode = HiThemeMode.LIGHT,
            uploadRepo = uploadRepo,
            configure = { vm ->
                vm.open(FullEditorRequest.NewThread(initialFid = 2))
            },
            afterLoad = { vm ->
                vm.selectCategory("56")
                vm.onSubjectChanged("多图上传条与内联缩略图块全状态基线")
                vm.onTextBlockChanged(vm.uiState.value.body.firstTextBlockId, "第一段图文说明：\n补充第二段文字，正文中仅展示缩略图块。", 8, 8)
                vm.onPickImages(listOf(uriInserted, uriUninserted, uriUploading, uriCompressing, uriFailed))
            },
            afterSecondIdle = { vm ->
                vm.uploadsFirstIdFor("810001")?.let { vm.onInsertUploadedImage(it) }
            },
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_draft_validation_light() {
        val draftKey = DraftKey.newThread("2")
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                draftKey.value to DraftEntity(draftKey.value, subject = "短标题", content = "上次未写完的草稿内容已经自动恢复。", updatedAt = 1_000L),
            ),
        ).apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo(typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery")))
            }
        }
        captureStandalone(
            name = "editor_phone_w411_draft_validation_light",
            themeMode = HiThemeMode.LIGHT,
            postRepo = postRepo,
            configure = { vm -> vm.open(FullEditorRequest.NewThread(initialFid = 2)) },
            afterLoad = { vm ->
                vm.selectCategory("56")
                vm.submit()
            },
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun editor_phone_w411_cooldown_error_dark() {
        val now = 20_000L
        val postRepo = FakePostRepository(nowMs = { now }).apply {
            submitHandler = { PostSubmitResult.NetworkError("timeout") }
        }
        captureStandalone(
            name = "editor_phone_w411_cooldown_error_dark",
            themeMode = HiThemeMode.DARK,
            postRepo = postRepo,
            nowMs = now,
            configure = { vm ->
                vm.open(
                    FullEditorRequest.fromHandoff(
                        FullEditorHandoff(tid = "2607001", fid = 2, text = "提交失败后内容完整保留，原地展示失败提示与重试按钮。", selectionStart = 20, selectionEnd = 20, threadAuthor = "linus_hi"),
                    ),
                )
            },
            afterLoad = { vm ->
                vm.submit()
            },
            afterSecondIdle = { _ ->
                postRepo.setCooldownUntil(now + 24_200L)
            },
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun editor_twopane_w640_modal_light() = captureInNextAppRoot(
        name = "editor_twopane_w640_modal_light",
        themeMode = ThemeMode.LIGHT,
        openViaForumNewThread = true,
        maxGlassSurfaces = 1,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun editor_threepane_w749_modal_light() = captureInNextAppRoot(
        name = "editor_threepane_w749_modal_light",
        themeMode = ThemeMode.LIGHT,
        openViaForumNewThread = false,
        maxGlassSurfaces = 3,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun editor_threepane_w933_modal_dark() = captureInNextAppRoot(
        name = "editor_threepane_w933_modal_dark",
        themeMode = ThemeMode.DARK,
        openViaForumNewThread = false,
        maxGlassSurfaces = 3,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun editor_tablet_w1280_rightpane_light() = captureInNextAppRoot(
        name = "editor_tablet_w1280_rightpane_light",
        themeMode = ThemeMode.LIGHT,
        openViaForumNewThread = false,
        maxGlassSurfaces = 3,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun editor_tablet_w1280_rightpane_dark() = captureInNextAppRoot(
        name = "editor_tablet_w1280_rightpane_dark",
        themeMode = ThemeMode.DARK,
        openViaForumNewThread = true,
        maxGlassSurfaces = 3,
    )

    private fun captureStandalone(
        name: String,
        themeMode: HiThemeMode,
        postRepo: FakePostRepository = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo(typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery", "19" to "站务")))
            }
        },
        uploadRepo: FakeImageUploadRepository = FakeImageUploadRepository(),
        nowMs: Long = 10_000L,
        configure: (FullEditorViewModel) -> Unit,
        afterLoad: ((FullEditorViewModel) -> Unit)? = null,
        afterSecondIdle: ((FullEditorViewModel) -> Unit)? = null,
    ) {
        val vm = FullEditorViewModel(postRepo, uploadRepo, FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { nowMs }
        configure(vm)
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        composeRule.setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                FullEditorScreen(viewModel = vm, onOpenLogin = {})
            }
        }
        composeRule.waitForIdle()
        if (afterLoad != null) {
            composeRule.runOnIdle { afterLoad(vm) }
            composeRule.waitForIdle()
        }
        if (afterSecondIdle != null) {
            composeRule.runOnIdle { afterSecondIdle(vm) }
            composeRule.waitForIdle()
        }
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun captureInNextAppRoot(
        name: String,
        themeMode: ThemeMode,
        openViaForumNewThread: Boolean,
        maxGlassSurfaces: Int,
    ) {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(themeMode = themeMode, themeSeed = HiColorPresets.Brand.id),
            richSampleContent = true,
        )
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(defaultSamplePrePostInfo(typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery", "19" to "站务")))
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }

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

        if (openViaForumNewThread) {
            composeRule.onNodeWithContentDescription("发新帖").performClick()
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                editorVm.selectCategory("56")
                editorVm.onSubjectChanged("多栏自适应完整编辑器发新帖演示")
                editorVm.onTextBlockChanged(editorVm.uiState.value.body.firstTextBlockId, "右栏宽度达到 560dp 时直接在右栏内展开；不足 560dp 时居中模态覆盖全部栏。", 0, 0)
            }
        } else {
            composeRule.onNodeWithText("折腾折叠屏客户端", substring = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("展开全屏").performClick()
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                editorVm.onTextBlockChanged(editorVm.uiState.value.body.firstTextBlockId, "从快速回复面板无损展开至完整编辑器，纯色表面零玻璃开销。", 0, 0)
            }
        }
        composeRule.waitForIdle()

        composeRule.assertGlassSurfaceCountAtMost(maxGlassSurfaces)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun FullEditorViewModel.uploadsFirstIdFor(imageId: String): String? =
        uiState.value.uploads.firstOrNull { it.imageIdOrNull == imageId }?.localId
}
