package net.jejer.hipda.feature.app

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3 验收项 2（全形态返回栈 5 级优先级）、验收项 3（R-E0 R2-S3 反例 B）与 R-E3 S2（真实 `OnBackPressedDispatcher` 优先级）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneBackStackTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pureBackPriority_fiveForumsAndFiveThreads_firstBackClearsDetail_secondBackReturnsToDefaultForum() {
        val vm = NextAppHostViewModel()
        listOf(6, 7, 59, 12, 6).forEach { fid -> vm.selectSidebarEntry(ShellSidebarEntry.Forum(fid)) }
        listOf("6001", "6002", "6003", "6004", "6005").forEach { tid ->
            vm.openThread(ThreadNavArgs(tid, "标题 $tid", 6))
        }
        assertEquals("6005", vm.state.value.selectedThread?.tid)

        assertTrue(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
        assertNull(vm.state.value.selectedThread)
        assertEquals(ForumListSource.Forum(6), vm.state.value.activeForumSource)

        assertTrue(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
        assertEquals(ForumListSource.Forum(2), vm.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.Forum(2), vm.state.value.selectedSidebarEntry)

        assertFalse(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
    }

    @Test
    fun pureBackPriority_allFiveLevels_executedInExactSpecOrder() {
        val vm = NextAppHostViewModel()
        vm.selectSidebarEntry(ShellSidebarEntry.Notify)
        vm.openThread(ThreadNavArgs("2001", "提醒主帖", 2))
        vm.pushUserProfile("88", "测试作者")
        vm.pushUserProfile("99", "二级用户")
        vm.toggleFullScreenReading()
        vm.openLoginOverlay(LoginOverlayMode.Login)

        // 优先级 1：关顶层浮层
        assertEquals(MultiPaneBackAction.CloseLoginOverlay, resolveMultiPaneBackAction(vm.state.value, ShellPaneLayoutMode.ExpandedThreePane))
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertNull(vm.state.value.loginOverlayMode)

        // 优先级 2：退全屏阅读
        assertEquals(MultiPaneBackAction.ExitFullScreen, resolveMultiPaneBackAction(vm.state.value, ShellPaneLayoutMode.ExpandedThreePane))
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertFalse(vm.state.value.isFullScreenReading)

        // 优先级 3：逐级弹右栏下钻栈
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertEquals(listOf(DetailDrilldownEntry.UserProfile("88", "测试作者")), vm.state.value.detailDrilldownStack)
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertTrue(vm.state.value.detailDrilldownStack.isEmpty())
        assertEquals("2001", vm.state.value.selectedThread?.tid)

        // 优先级 4：清空右栏根帖子回占位
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertNull(vm.state.value.selectedThread)
        assertEquals(ShellSidebarEntry.Notify, vm.state.value.selectedSidebarEntry)

        // 优先级 5：非默认入口回默认论坛，再按交还系统
        assertTrue(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
        assertEquals(ShellSidebarEntry.Forum(2), vm.state.value.selectedSidebarEntry)
        assertFalse(vm.handleBack(ShellPaneLayoutMode.ExpandedThreePane))
    }

    @Test
    fun hingeTwoPane_settingsAndMineSubList_demoteCorrectlyOnBack() {
        val vm = NextAppHostViewModel()
        vm.selectSidebarEntry(ShellSidebarEntry.Settings)
        val settingsRoles = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.SettingsGroups, settingsRoles.left)
        assertEquals(HingeRightPaneRole.SettingsDetail, settingsRoles.right)

        assertTrue(vm.handleBack(ShellPaneLayoutMode.HingeTwoPane))
        val afterSettingsBack = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.MineMenu, afterSettingsBack.left)
        assertEquals(HingeRightPaneRole.EmptyPlaceholder, afterSettingsBack.right)

        vm.selectSidebarEntry(ShellSidebarEntry.History)
        vm.openThread(ThreadNavArgs("3001", "历史帖子", 2))
        val promoted = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.PromotedMineSubList, promoted.left)
        assertEquals(HingeRightPaneRole.ThreadDetail, promoted.right)
        assertEquals(ShellSidebarEntry.History, promoted.mineSubEntry)

        assertTrue(vm.handleBack(ShellPaneLayoutMode.HingeTwoPane))
        val demoted = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.MineMenu, demoted.left)
        assertEquals(HingeRightPaneRole.MineSubList, demoted.right)
        assertEquals(ShellSidebarEntry.History, demoted.mineSubEntry)
    }

    @Test
    @Config(qualifiers = "w640dp-h1024dp-xhdpi")
    fun sidebarSinglePane_640dp_clickingLeftEntryClearsDetailAndDrilldown() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("1", "楼层作者1") }
        composeRule.waitForIdle()
        assertEquals(1, harness.hostViewModel.state.value.detailDrilldownStack.size)

        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(6))).performClick()
        composeRule.waitForIdle()

        assertNull("600–679dp 点左栏入口必须清空 selectedThread", harness.hostViewModel.state.value.selectedThread)
        assertTrue("600–679dp 点左栏入口必须清空下钻栈", harness.hostViewModel.state.value.detailDrilldownStack.isEmpty())
        assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        composeRule.onNodeWithText("测试帖子标题 6001").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun rE0S3_openThreadThenSwitchForumOrTodayNew_clearsRightPaneAndClearsOldViewModelStore() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.createdDetailVms.size)
        val firstVm = harness.createdDetailVms[0]
        assertTrue(harness.clearedDetailVms.isEmpty())

        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(6))).performClick()
        composeRule.waitForIdle()

        assertNull("切版块后右栏必须清空", harness.hostViewModel.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
        assertTrue("旧帖 ThreadDetailViewModel 必须被 clear()", firstVm in harness.clearedDetailVms)

        composeRule.onNodeWithText("测试帖子标题 6001").performClick()
        composeRule.waitForIdle()
        assertEquals(2, harness.createdDetailVms.size)
        val secondVm = harness.createdDetailVms[1]

        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.TodayNew)).performClick()
        composeRule.waitForIdle()

        assertNull("切今日新帖后右栏必须清空", harness.hostViewModel.state.value.selectedThread)
        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertTrue("切今日新帖必须 clear() 旧帖 ViewModelStore", secondVm in harness.clearedDetailVms)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun rE0S3_hingeTwoPane_openThreadThenSwitchTopForumTab_clearsRightPaneAndOldStore() {
        val hingeInfo = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(hingeInfo)) {
            val harness = MultiPaneTestHarness()
            composeRule.setContent { harness.RenderRoot() }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("测试帖子标题 1001").performClick()
            composeRule.waitForIdle()
            val openedVm = harness.createdDetailVms.single()
            assertNotNull(harness.hostViewModel.state.value.selectedThread)

            composeRule.onNodeWithText("今日新帖").performClick()
            composeRule.waitForIdle()

            assertNull("铰链顶栏切版块必须清空右栏", harness.hostViewModel.state.value.selectedThread)
            assertTrue("铰链顶栏切版块必须触发旧帖 ViewModelStore.clear()", openedVm in harness.clearedDetailVms)
        }
    }

    /** 闭合 R-E3 S2：通过真实宿主 `OnBackPressedDispatcher.onBackPressed()` 验证优先级 1（240dp 浮层侧栏）先于右栏帖子关闭。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun s2_modalDrawerOverlay_priority1_dismissesViaRealSystemBackBeforeDetailThread() {
        val harness = MultiPaneTestHarness()
        var hostBackDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            harness.RenderRoot()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 第 1 次真实系统返回：仅关闭 240dp 浮层，右栏帖子仍保持打开
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        assertEquals("第 1 次系统返回不得误关右栏帖子", "1001", harness.hostViewModel.state.value.selectedThread?.tid)

        // 第 2 次真实系统返回：关闭右栏帖子回到占位
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertNull("第 2 次系统返回应关闭右栏帖子", harness.hostViewModel.state.value.selectedThread)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }
}
