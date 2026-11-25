package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellDestination
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
 * E3 验收项 2（全形态返回栈 5 级优先级）与验收项 3（R-E0 R2-S3 反例 B：开帖切版块/今日新帖自动清右栏并 clear 旧 Store）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneBackStackTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pureBackPriority_fiveForumsAndFiveThreads_firstBackClearsDetail_secondBackReturnsToDefaultForum() {
        val vm = NextAppHostViewModel()
        // 连续点 5 个不同版块
        listOf(6, 7, 59, 12, 6).forEach { fid -> vm.selectSidebarEntry(ShellSidebarEntry.Forum(fid)) }
        // 在当前版块中栏连点 5 篇帖子（仅替换右栏根，绝不压栈）
        listOf("6001", "6002", "6003", "6004", "6005").forEach { tid ->
            vm.openThread(ThreadNavArgs(tid, "标题 $tid", 6))
        }
        assertEquals("6005", vm.state.value.selectedThread?.tid)

        // 第 1 次返回：直接回「列表 + 空占位」，不回退到 6004
        assertTrue(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
        assertNull(vm.state.value.selectedThread)
        assertEquals(ForumListSource.Forum(6), vm.state.value.activeForumSource)

        // 第 2 次返回：直接回默认论坛入口（fid=2），不回退到之前看过的 12/59/7
        assertTrue(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
        assertEquals(ForumListSource.Forum(2), vm.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.Forum(2), vm.state.value.selectedSidebarEntry)

        // 第 3 次返回：已在默认入口，交还系统
        assertFalse(vm.handleBack(ShellPaneLayoutMode.CompactThreePane))
    }

    @Test
    fun pureBackPriority_allFiveLevels_executedInExactSpecOrder() {
        val vm = NextAppHostViewModel()
        vm.selectSidebarEntry(ShellSidebarEntry.Notify)
        vm.openThread(ThreadNavArgs("2001", "提醒主帖", 2))
        vm.pushUserProfile("88", "测试作者")
        vm.pushLinkedThread(ThreadNavArgs("2002", "站内链接帖", 6))
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

        // 优先级 3：逐级弹右栏下钻栈（LinkedThread -> UserProfile）
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
        // 1. 设置在铰链双栏下按返回 -> 退回「左：我的菜单」
        vm.selectSidebarEntry(ShellSidebarEntry.Settings)
        val settingsRoles = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.SettingsGroups, settingsRoles.left)
        assertEquals(HingeRightPaneRole.SettingsDetail, settingsRoles.right)

        assertTrue(vm.handleBack(ShellPaneLayoutMode.HingeTwoPane))
        val afterSettingsBack = resolveHingeTwoPaneRoles(vm.state.value)
        assertEquals(HingeLeftPaneRole.MineMenu, afterSettingsBack.left)
        assertEquals(HingeRightPaneRole.EmptyPlaceholder, afterSettingsBack.right)

        // 2. 浏览历史选帖晋升 -> 按返回退回「左：我的菜单 | 右：浏览历史」
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

        // 640dp 图标轨点左栏另一版块：立即清空当前详情与下钻栈并替换根列表
        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(6))).performClick()
        composeRule.waitForIdle()

        assertNull("600–679dp 点左栏入口必须清空 selectedThread", harness.hostViewModel.state.value.selectedThread)
        assertTrue("600–679dp 点左栏入口必须清空下钻栈", harness.hostViewModel.state.value.detailDrilldownStack.isEmpty())
        assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        composeRule.onNodeWithText("测试帖子标题 6001").assertIsDisplayed()
    }

    /**
     * 闭合 R-E0 R2-S3 反例 B：开着帖子切换左栏版块 / 今日新帖 / 铰链顶栏版块标签时，
     * 必须自动清空右栏 `selectedThread` 并立即触发旧帖 `ViewModelStore.clear()`（`onCleared()` 回调）。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun rE0S3_openThreadThenSwitchForumOrTodayNew_clearsRightPaneAndClearsOldViewModelStore() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        // 1. 三栏打开 Discovery 的 1001 帖 -> 左栏切 Buy & Sell (fid=6)
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

        // 2. 在 fid=6 打开 6001 帖 -> 左栏切 TodayNew（今日新帖）
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

            // 铰链双栏保留顶栏横向版块标签行：点击「今日新帖」标签
            composeRule.onNodeWithText("今日新帖").performClick()
            composeRule.waitForIdle()

            assertNull("铰链顶栏切版块必须清空右栏", harness.hostViewModel.state.value.selectedThread)
            assertTrue("铰链顶栏切版块必须触发旧帖 ViewModelStore.clear()", openedVm in harness.clearedDetailVms)
        }
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun modalDrawerOverlay_priority1_dismissesBeforeMultiPaneDetailBack() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 点击遮罩收回 240dp 浮层，右栏帖子必须保持打开不受影响
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalScrim).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        assertEquals("1001", harness.hostViewModel.state.value.selectedThread?.tid)
    }
}
