package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.asStateFlow
import net.jejer.hipda.feature.app.MultiPaneSnackbarController
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M7-5b 验收 A-d：触发未知外部链接后，Snackbar 时长为 `SnackbarDuration.Long`（10s），
 * 超过 `Short`（4s）后仍可见，10s 后正常消失；其余无按钮错误提示保持 `Short` 不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UnsupportedDeepLinkDurationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aD_unsupportedExternalUrl_snackbarDurationIsLongAndOutlastsShortTimeout() {
        val harness = MultiPaneTestHarness()
        var capturedController: PaneSnackbarController? = null
        val unknownUrl = "https://www.4d4y.com/forum/misc.php?action=help"

        val probeRenderer = object : SettingsProfilePaneRenderer {
            @Composable
            override fun SettingsGroupsContent(
                selectedGroup: SettingsGroup,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onSelectGroup: (SettingsGroup) -> Unit,
                contentPadding: PaddingValues,
                listState: LazyListState,
            ) = Unit

            @Composable
            override fun SettingsDetailContent(
                group: SettingsGroup,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) = Unit

            @Composable
            override fun UserProfileContent(
                uid: String,
                username: String,
                showBack: Boolean,
                onBack: () -> Unit,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenSmsConversation: (SmsConversationArgs) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) {
                val controller = LocalPaneSnackbarController.current
                SideEffect { capturedController = controller }
                Box(Modifier.fillMaxSize())
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    settingsProfilePaneRenderer = probeRenderer,
                    externalLinkOpener = ExternalLinkOpener { true },
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // 借一次下钻捕获 NextAppRoot 内部真实 MultiPaneSnackbarController，随即退回
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("1", "probe") }
        composeRule.waitForIdle()
        val controller = requireNotNull(capturedController as? MultiPaneSnackbarController)
        composeRule.runOnIdle { harness.hostViewModel.popDetailDrilldown() }
        composeRule.waitForIdle()

        // 触发未知外链深链
        composeRule.runOnIdle {
            harness.hostViewModel.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(unknownUrl)))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("无法识别该链接").assertIsDisplayed()
        composeRule.onNodeWithText("在浏览器中打开").assertIsDisplayed()
        assertEquals(
            "未知外链提示 Snackbar 时长必须为 SnackbarDuration.Long",
            SnackbarDuration.Long,
            controller.hostState.currentSnackbarData?.visuals?.duration,
        )

        // 推进 5 秒（大于 Short 的 4 秒，小于 Long 的 10 秒）仍应显示
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("无法识别该链接").assertIsDisplayed()

        // 再推进 6 秒（累计 11 秒 > Long 10 秒）后自动消失
        composeRule.mainClock.advanceTimeBy(6_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("无法识别该链接").assertDoesNotExist()
    }
}
