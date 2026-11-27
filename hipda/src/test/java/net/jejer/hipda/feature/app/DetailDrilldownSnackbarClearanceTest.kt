package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 M7-P2 e 项（R-E6 O1）：
 * 详情下钻到无底部工具条的 `UserProfile` 时，Snackbar 底部余量不含 56dp 工具条高度；
 * 下钻到带底部输入栏的 `SmsConversation` 或返回帖子后，56dp 底部避让余量恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DetailDrilldownSnackbarClearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun singlePane_userProfileDrilldownDropsToolbarClearance_smsAndThreadRestoreIt() {
        verifyClearanceAcrossDrilldownTransitions()
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun threePane_userProfileDrilldownDropsToolbarClearance_smsAndThreadRestoreIt() {
        verifyClearanceAcrossDrilldownTransitions()
    }

    private fun verifyClearanceAcrossDrilldownTransitions() {
        val harness = MultiPaneTestHarness()
        var activeSnackbarController: PaneSnackbarController? = null
        lateinit var scope: CoroutineScope

        val settingsProfileRenderer = object : SettingsProfilePaneRenderer {
            @Composable
            override fun SettingsGroupsContent(
                selectedGroup: SettingsGroup,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onSelectGroup: (SettingsGroup) -> Unit,
                contentPadding: PaddingValues,
                listState: androidx.compose.foundation.lazy.LazyListState,
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
                SideEffect { activeSnackbarController = controller }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("资料页:$username")
                }
            }
        }

        val messagesRenderer = object : MessagesPaneRenderer {
            @Composable
            override fun ListContent(
                entry: ShellSidebarEntry,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                selectedConversationUid: String?,
                onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
                onOpenConversation: (SmsConversationArgs) -> Unit,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenUserProfile: (uid: String, username: String) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
                listState: androidx.compose.foundation.lazy.LazyListState,
            ) = Unit

            @Composable
            override fun ConversationContent(
                args: SmsConversationArgs,
                showBack: Boolean,
                onBack: () -> Unit,
                onOpenUserProfile: (uid: String, username: String) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) {
                val controller = LocalPaneSnackbarController.current
                SideEffect { activeSnackbarController = controller }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("短消息:${args.uid}")
                }
            }
        }

        harness.hostViewModel.openThread(ThreadNavArgs(tid = "1001", title = "余量测试帖", fid = 2))

        composeRule.setContent {
            scope = rememberCoroutineScope()
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                        messagesPaneRenderer = messagesRenderer,
                        settingsProfilePaneRenderer = settingsProfileRenderer,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 先下钻一次拿到 NextAppRoot 内部的 MultiPaneSnackbarController，再立刻返回帖子页
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("42", "alice") }
        composeRule.waitForIdle()
        val controller = requireNotNull(activeSnackbarController as? MultiPaneSnackbarController)
        composeRule.runOnIdle { harness.hostViewModel.popDetailDrilldown() }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBottomDp = with(density) { composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom.toDp().value }

        fun measureDetailSnackbarBottomGapDp(message: String): Float {
            composeRule.runOnIdle {
                controller.hostState.currentSnackbarData?.dismiss()
                scope.launch { controller.show(message, PaneSnackbarTarget.DetailPane) }
            }
            composeRule.waitForIdle()
            val snackBottomDp = with(density) {
                composeRule.onNodeWithText(message).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom.toDp().value
            }
            return rootBottomDp - snackBottomDp
        }

        // 1. 顶层是 Thread：Snackbar 避让 56dp 底部工具条（底距约 64dp）
        val threadGapDp = measureDetailSnackbarBottomGapDp("帖子提示")

        // 2. 下钻到无底部工具条的 UserProfile：不再额外加 56dp 余量（底距降低 48dp）
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("42", "alice") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("资料页:alice").assertIsDisplayed()
        val profileGapDp = measureDetailSnackbarBottomGapDp("资料页提示")
        val dropDp = threadGapDp - profileGapDp
        assertTrue(
            "下钻 UserProfile 后底部余量应减少约 48dp（去掉 56dp 工具条余量），实际 threadGap=$threadGapDp, profileGap=$profileGapDp, drop=$dropDp",
            dropDp in 44f..52f,
        )

        // 3. 继续下钻到带底部输入栏的 SmsConversation：保留 56dp 避让余量
        composeRule.runOnIdle { harness.hostViewModel.pushSmsConversation(SmsConversationArgs("42", "alice")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("短消息:42").assertIsDisplayed()
        val smsGapDp = measureDetailSnackbarBottomGapDp("短消息提示")
        assertTrue(
            "下钻 SmsConversation 应保持与帖子一致的底部余量，实际 threadGap=$threadGapDp, smsGap=$smsGapDp",
            abs(smsGapDp - threadGapDp) <= 2f,
        )

        // 4. 返回 UserProfile 再返回 Thread：余量先降后恢复
        composeRule.runOnIdle { harness.hostViewModel.popDetailDrilldown() }
        composeRule.waitForIdle()
        val backToProfileGapDp = measureDetailSnackbarBottomGapDp("返回资料页提示")
        assertTrue(
            "返回 UserProfile 后底部余量应回落，实际 backToProfileGap=$backToProfileGapDp, profileGap=$profileGapDp",
            abs(backToProfileGapDp - profileGapDp) <= 2f,
        )

        composeRule.runOnIdle { harness.hostViewModel.popDetailDrilldown() }
        composeRule.waitForIdle()
        val restoredThreadGapDp = measureDetailSnackbarBottomGapDp("回到帖子提示")
        assertTrue(
            "回到帖子后应恢复 56dp 底部工具条余量，实际 threadGap=$threadGapDp, restored=$restoredThreadGapDp",
            abs(restoredThreadGapDp - threadGapDp) <= 2f,
        )
    }
}
