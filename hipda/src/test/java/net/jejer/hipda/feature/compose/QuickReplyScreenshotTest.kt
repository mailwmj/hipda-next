package net.jejer.hipda.feature.compose

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
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
 * E5 毛玻璃快速回复面板 9 张 Roborazzi 截图基线（前缀 `quick_reply_`，按负责人裁定 #2 以 E6/E7 未绑定状态录制）：
 * 1. 单栏 `411dp` 浅/深（常规回复态）；
 * 2. 单栏 `411dp` 引用楼层 + 草稿恢复横条态（浅色）；
 * 3. 单栏 `411dp` 30 秒冷却倒计时态（深色）；
 * 4. 单栏 `411dp` 未登录原地提示态（浅色）；
 * 5. 矮屏横屏 `891×411dp` 弹键盘紧凑限高态（浅/深）；
 * 6. 三栏 `749dp`（浅色）与 `933dp`（深色）右栏内展开态（暗罩与面板严格限定右栏）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class QuickReplyScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun quick_reply_phone_w411_light() = captureSinglePane(
        name = "quick_reply_phone_w411_light",
        themeMode = HiThemeMode.LIGHT,
        prefillText = "这套液态玻璃快速回复面板在读长帖时非常顺手。",
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun quick_reply_phone_w411_dark() = captureSinglePane(
        name = "quick_reply_phone_w411_dark",
        themeMode = HiThemeMode.DARK,
        prefillText = "深色模式下 12dp 模糊与 74% 护读着色对比度清晰自然。",
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun quick_reply_phone_w411_quote_draft_light() = captureSinglePane(
        name = "quick_reply_phone_w411_quote_draft_light",
        themeMode = HiThemeMode.LIGHT,
        quoteFloor2 = true,
        persistedDraftText = "刚才没写完的草稿内容已经自动恢复。",
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun quick_reply_phone_w411_cooldown_dark() = captureSinglePane(
        name = "quick_reply_phone_w411_cooldown_dark",
        themeMode = HiThemeMode.DARK,
        prefillText = "冷却倒计时期间依然可以继续打字编辑下一条回复。",
        cooldownSecondsRemaining = 28,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun quick_reply_phone_w411_guest_light() = captureSinglePane(
        name = "quick_reply_phone_w411_guest_light",
        themeMode = HiThemeMode.LIGHT,
        loggedIn = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun quick_reply_landscape_891x411_ime_compact_light() = captureSinglePane(
        name = "quick_reply_landscape_891x411_ime_compact_light",
        themeMode = HiThemeMode.LIGHT,
        prefillText = "横屏弹键盘后自动压成紧凑输入行，不溢出不截断。",
        simulatedImeDp = 240,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun quick_reply_landscape_891x411_ime_compact_dark() = captureSinglePane(
        name = "quick_reply_landscape_891x411_ime_compact_dark",
        themeMode = HiThemeMode.DARK,
        prefillText = "横屏深色弹键盘紧凑限高态。",
        simulatedImeDp = 240,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun quick_reply_threepane_w749_light() = captureThreePane(
        name = "quick_reply_threepane_w749_light",
        themeMode = ThemeMode.LIGHT,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun quick_reply_threepane_w933_dark() = captureThreePane(
        name = "quick_reply_threepane_w933_dark",
        themeMode = ThemeMode.DARK,
    )

    private fun captureSinglePane(
        name: String,
        themeMode: HiThemeMode,
        prefillText: String = "",
        persistedDraftText: String? = null,
        quoteFloor2: Boolean = false,
        cooldownSecondsRemaining: Int = 0,
        loggedIn: Boolean = true,
        simulatedImeDp: Int = 0,
    ) {
        val now = 20_000L
        val tid = "2607001"
        val initialDrafts = persistedDraftText?.let {
            val key = DraftKey.reply(tid)
            mapOf(key.value to DraftEntity(key.value, content = it, updatedAt = now - 5_000L))
        }.orEmpty()
        val postRepo = FakePostRepository(nowMs = { now }, initialDrafts = initialDrafts).apply {
            if (cooldownSecondsRemaining > 0) setCooldownUntil(now + cooldownSecondsRemaining * 1_000L - 200L)
        }
        val authRepo = FakeQuickReplyAuthRepository(
            initialAccount = if (loggedIn) FakeQuickReplyAuthRepository().currentAccount.value else null,
        )
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { now })
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 2), createIdleComposeGateway(), authRepo)
        val staticScale = object : MotionDurationScale { override val scaleFactor: Float = 0f }
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = tid, title = "毛玻璃快速回复面板视觉基线", fid = 2, forumName = "Discovery", author = "linus_hi"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = detailVm,
                        quickReplyViewModel = qrVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        if (quoteFloor2) {
            composeRule.onNodeWithText("第 1 页第 2 楼正文", substring = true).performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("引用回复").performClick()
        } else {
            composeRule.onNodeWithText("回复").performClick()
        }
        composeRule.waitForIdle()

        if (prefillText.isNotEmpty()) {
            composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput(prefillText)
            composeRule.waitForIdle()
        }
        if (simulatedImeDp > 0) {
            val imePx = with(composeRule.density) { simulatedImeDp.dp.roundToPx() }
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    requireNotNull(hostView),
                    WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx)).build(),
                )
            }
            composeRule.waitForIdle()
        }

        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun captureThreePane(name: String, themeMode: ThemeMode) {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(themeMode = themeMode, themeSeed = HiColorPresets.Brand.id),
            richSampleContent = true,
        )
        val postRepo = FakePostRepository(
            initialDrafts = mapOf(
                DraftKey.reply("1001").value to DraftEntity(DraftKey.reply("1001").value, content = "三栏右栏内升起快速回复，左中两栏可继续浏览。", updatedAt = 1_000L),
            ),
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
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("折腾折叠屏客户端", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        composeRule.assertGlassSurfaceCountAtMost(3)
        composeRule.onRoot().captureHiSnapshot(name)
    }
}
