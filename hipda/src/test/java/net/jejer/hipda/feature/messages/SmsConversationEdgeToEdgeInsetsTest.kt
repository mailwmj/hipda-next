package net.jejer.hipda.feature.messages

import android.app.Application
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.ui.designsystem.HiTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M7-3 验收 4（短消息对话页全面屏 Insets）：验证三键导航栏（48dp）与 IME 软键盘（280dp）共存时
 * `SmsConversationScreen` 底部纯色输入条贴合键盘且无双倍 padding，收起键盘后恢复完整导航栏避让。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 33, 35, 36], qualifiers = "w411dp-h891dp-xhdpi")
class SmsConversationEdgeToEdgeInsetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun smsConversationInputBar_navBarAndImeCoexist_hasZeroDoubleBottomPadding() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val clock = AtomicLong(1_700_000_000_000L)
            val smsRepo = FakeSmsRepository()
            val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)
            val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), gateway) { clock.get() }
            var hostView: View? = null

            composeRule.setContent {
                hostView = LocalView.current
                HiTheme {
                    SmsConversationScreen(
                        args = SmsConversationArgs(uid = "42", username = "Alice"),
                        showBack = true,
                        onBack = {},
                        onOpenUserProfile = { _, _ -> },
                        onOpenLogin = {},
                        contentPadding = PaddingValues(bottom = 48.dp),
                        viewModel = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            composeRule.waitForIdle()

            val density = composeRule.density
            val rootHeightPx = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
            val navPx = with(density) { 48.dp.roundToPx() }
            val imePx = with(density) { 280.dp.roundToPx() }

            // 1. 仅三键导航栏（48dp）、IME 关闭：发送按钮底部应保留 48dp 导航栏避让
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    requireNotNull(hostView),
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                        .build(),
                )
            }
            composeRule.waitForIdle()
            val sendIdle = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val idleGapFromBottomDp = with(density) { (rootHeightPx - sendIdle.bottom).toDp() }
            assertTrue("IME 关闭时输入条发送按钮应避让 48dp 三键导航栏，实际距底 $idleGapFromBottomDp", idleGapFromBottomDp >= 52.dp)

            // 2. API 30+（33/35/36）支持原生 WindowInsets.Type.ime()：48dp 导航栏 + 280dp IME 共存时绝不可叠加 48dp 双倍留白
            // （API 28 平台 WindowInsets 无独立 IME 类型，系统通过 adjustResize 缩放窗口而非叠加 Type.ime）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                composeRule.runOnUiThread {
                    ViewCompat.dispatchApplyWindowInsets(
                        requireNotNull(hostView),
                        WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                            .build(),
                    )
                }
                composeRule.waitForIdle()
                val sendWithIme = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val imeGapFromBottomDp = with(density) { (rootHeightPx - sendWithIme.bottom).toDp() }
                assertTrue(
                    "IME 打开时发送按钮必须位于 280dp 键盘之上且无 48dp 双倍留白（期望 ~292dp，实际 $imeGapFromBottomDp）",
                    imeGapFromBottomDp in 284.dp..308.dp,
                )
            }
        } finally {
            scope.cancel()
        }
    }
}
