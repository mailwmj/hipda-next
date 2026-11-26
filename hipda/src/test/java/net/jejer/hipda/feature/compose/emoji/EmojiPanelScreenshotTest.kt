package net.jejer.hipda.feature.compose.emoji

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.*
import net.jejer.hipda.feature.messages.FakeAuthRepository
import net.jejer.hipda.feature.messages.FakeSmsRepository
import net.jejer.hipda.feature.messages.SmsConversationScreen
import net.jejer.hipda.feature.messages.SmsConversationViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E7 表情选择面板 10 张 Roborazzi 截图基线（前缀 `emoji_panel_`）：
 * 1. 默认 / 酷猴（浅+深） / 呆呆（浅+深）三分类面板切图（5 张）；
 * 2. `411dp` 快速回复内表情面板、`411dp` 完整编辑器内表情面板、`411dp` 短消息对话内表情面板（3 张）；
 * 3. `749dp` 三栏右栏内表情面板、`1280dp` 平板右栏自适应多列、`891×411dp` 矮屏 `< 480dp` 35% 限高态（3 张）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class EmojiPanelScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val renderer = EmojiPanelRendererImpl()
    private val emojiSlot: @Composable (QuickReplyEmojiPanelHost) -> Unit = { host -> renderer.Content(host, Modifier) }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_default_w411_light() = captureStandaloneCategory("emoji_panel_default_w411_light", HiThemeMode.LIGHT, tabCd = null)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_monkey_w411_light() = captureStandaloneCategory("emoji_panel_monkey_w411_light", HiThemeMode.LIGHT, tabCd = "酷猴表情")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_monkey_w411_dark() = captureStandaloneCategory("emoji_panel_monkey_w411_dark", HiThemeMode.DARK, tabCd = "酷猴表情")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_dumb_w411_light() = captureStandaloneCategory("emoji_panel_dumb_w411_light", HiThemeMode.LIGHT, tabCd = "呆呆表情")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_dumb_w411_dark() = captureStandaloneCategory("emoji_panel_dumb_w411_dark", HiThemeMode.DARK, tabCd = "呆呆表情")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_quick_reply_w411_light() = captureQuickReplySinglePane("emoji_panel_quick_reply_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_full_editor_w411_dark() {
        val vm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2702001", 2, "在完整编辑器中插入论坛表情 :) ", 16, 16, quotePid = "102", quoteAuthor = "geek_reader", quoteFloor = 2, threadAuthor = "linus_hi")))
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                FullEditorScreen(viewModel = vm, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("酷猴表情").performClick()
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot("emoji_panel_full_editor_w411_dark")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun emoji_panel_threepane_w749_light() = captureThreePaneQuickReply("emoji_panel_threepane_w749_light", ThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun emoji_panel_tablet_w1280_dark() = captureThreePaneQuickReply("emoji_panel_tablet_w1280_dark", ThemeMode.DARK, tabCd = "呆呆表情")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun emoji_panel_short_landscape_891x411_light() = captureQuickReplySinglePane("emoji_panel_short_landscape_891x411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun emoji_panel_sms_w411_light() {
        val offlineLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong(1_700_000_000_000L)
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(
                SmsMessageItem("20001", "linus_cn", null, "10:15", "周末出来喝咖啡吗 :)", false, false),
                SmsMessageItem("10000", "HiUser", null, "10:18", "没问题，老地方见 {:2_41:}", false, true),
            ),
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)) { clock.get() }
        vm.bindConversation(SmsConversationArgs("20001", "linus_cn"))
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        try {
            composeRule.setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    CompositionLocalProvider(LocalHiImageLoader provides offlineLoader) {
                        SmsConversationScreen(
                            args = SmsConversationArgs("20001", "linus_cn"),
                            showBack = true,
                            onBack = {},
                            onOpenUserProfile = { _, _ -> },
                            onOpenLogin = {},
                            contentPadding = PaddingValues(0.dp),
                            viewModel = vm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            vm.onInputChange("好呀，下午三点 ")
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.assertGlassSurfaceCountAtMost(0)
            composeRule.onRoot().captureHiSnapshot("emoji_panel_sms_w411_light")
        } finally {
            scope.cancel()
            offlineLoader.shutdown()
        }
    }

    private fun captureStandaloneCategory(name: String, themeMode: HiThemeMode, tabCd: String?) {
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }
        composeRule.setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.BottomCenter) {
                    EmojiPickerPanel(
                        host = QuickReplyEmojiPanelHost(280.dp, 891.dp, {}, {}, {}),
                        modifier = Modifier.height(280.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        if (tabCd != null) {
            composeRule.onNodeWithContentDescription(tabCd).performClick()
            composeRule.waitForIdle()
        }
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun captureQuickReplySinglePane(name: String, themeMode: HiThemeMode) {
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 20_000L })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 2), createIdleComposeGateway(), FakeQuickReplyAuthRepository())
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }

        composeRule.setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    ThreadDetailPane(
                        args = ThreadNavArgs("2702002", "表情面板快速回复基线", 2, "Discovery", "linus_hi"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = detailVm,
                        quickReplyViewModel = qrVm,
                        onExpandToFullEditor = {},
                        emojiPanelSlot = emojiSlot,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("快速回复内原地切表情面板 :) ")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun captureThreePaneQuickReply(name: String, themeMode: ThemeMode, tabCd: String? = null) {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(themeMode = themeMode, themeSeed = HiColorPresets.Brand.id),
            richSampleContent = true,
        )
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(DraftKey.reply("1001").value to DraftEntity(DraftKey.reply("1001").value, content = "三栏右栏表情面板自适应多列 :) ", updatedAt = 1_000L)),
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
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
                        emojiPanelSlot = emojiSlot,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("折腾折叠屏客户端", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        if (tabCd != null) {
            composeRule.onNodeWithContentDescription(tabCd).performClick()
            composeRule.waitForIdle()
        }
        composeRule.assertGlassSurfaceCountAtMost(3)
        composeRule.onRoot().captureHiSnapshot(name)
    }
}
