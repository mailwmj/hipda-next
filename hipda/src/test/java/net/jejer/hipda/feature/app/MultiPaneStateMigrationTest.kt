package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.MutableStateFlow
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3 验收项 2（B4-E3 ② 非论坛入口 `History` / `Notify` 展平三栏 ↔ 半折铰链双栏无损晋升与回退、全宽迁移 0 额外请求）
 * 与验收项 3（R-E0 R2-S3 切 `tid` 无键局部态隔离 + R-E0 S4 `StateRestorationTester` 进程回收双 `LazyListState` 恢复）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneStateMigrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun paneMatcher(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test
    @Config(qualifiers = "w1280dp-h1024dp-xhdpi")
    fun allWidthTransitions_411_559_640_749_860_1280_preserveViewModelScrollAndZeroExtraRequests() {
        val harness = MultiPaneTestHarness()
        val windowSize = mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1002").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()
        scrollFirstListBy(900f)
        val detailScrollBefore = readFirstListScroll()
        assertTrue(detailScrollBefore > 0f)
        val listCalls = harness.threadListRepo.refreshCalls.get()
        val detailCalls = harness.threadDetailRepo.loadPageCalls.get()

        val widths = listOf(
            DpSize(559.dp, 749.dp),
            DpSize(640.dp, 1024.dp),
            DpSize(749.dp, 559.dp),
            DpSize(860.dp, 744.dp),
            DpSize(1280.dp, 800.dp),
            DpSize(411.dp, 891.dp),
        )
        widths.forEach { target ->
            windowSize.value = target
            composeRule.waitForIdle()
            assertEquals("切至 $target 不得新增列表请求", listCalls, harness.threadListRepo.refreshCalls.get())
            assertEquals("切至 $target 不得新增详情请求", detailCalls, harness.threadDetailRepo.loadPageCalls.get())
            assertEquals("切至 $target 不得重建 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
            val currentVm = ViewModelProvider(
                harness.hostViewModel.viewModelStoreOwnerForThread("1002", harness.vmFactory),
            )["thread:1002", ThreadDetailViewModel::class.java]
            assertSame("切至 $target 必须保持同一 ThreadDetailViewModel 实例", detailVm, currentVm)
        }
        assertEquals("绕全宽度回 411dp 后详情滚动位必须保持不变", detailScrollBefore, readFirstListScroll(), 2f)
    }

    /**
     * 闭合 R-BATCH5 B4-E3 ②：从 `History` 与 `Notify` 两个非论坛入口选帖后，
     * 展平三栏（`isSeparating=false`）↔ 半折铰链双栏（`isSeparating=true`）↔ 按返回键：
     * 左栏严格承接原来源子列表（绝不跳回论坛），帖子 VM/滚动位零丢失，返回准确回退至「我的菜单 | 浏览历史」或「提醒占位」。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun b4e3_2_nonForumEntries_historyAndNotify_flatThreePaneToHingeTwoPaneAndBack_losslessPromotionAndDemotion() {
        val flatLayout = verticalHingeLayoutInfo(371f, 378f, 559f, density = 2f, isSeparating = false)
        val hingeLayout = verticalHingeLayoutInfo(371f, 378f, 559f, density = 2f, isSeparating = true)
        val layoutFlow = MutableStateFlow<WindowLayoutInfo>(flatLayout)

        withWindowLayoutFlow(layoutFlow) {
            val harness = MultiPaneTestHarness()
            composeRule.setContent {
                harness.RenderRoot(
                    secondaryListContent = { entry, showBack, onBack, padding, listState, onOpenThread ->
                        val headerTitle = when (entry) {
                            ShellSidebarEntry.History -> "浏览历史列表"
                            ShellSidebarEntry.Notify -> "提醒消息列表"
                            else -> "其它入口:${entry::class.simpleName}"
                        }
                        val sampleTid = if (entry == ShellSidebarEntry.History) "7001" else "8001"
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            Text(headerTitle)
                            if (showBack && onBack != null) {
                                Button(onClick = onBack) { Text("返回我的菜单") }
                            }
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items((1..15).toList()) { idx ->
                                    val tid = if (idx == 1) sampleTid else "${sampleTid}_$idx"
                                    Text(
                                        text = "$headerTitle 条目 $tid",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenThread(ThreadNavArgs(tid, "来自$headerTitle 的帖子 $tid", 2)) }
                                            .padding(12.dp),
                                    )
                                }
                            }
                        }
                    },
                )
            }
            composeRule.waitForIdle()

            // ---- 场景 1：History 入口 ----
            composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.History) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("浏览历史列表 条目 7001").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            val histVm = harness.createdDetailVms.last()
            val detailCallsBeforeFold = harness.threadDetailRepo.loadPageCalls.get()

            // 展平三栏 → 半折铰链双栏
            layoutFlow.value = hingeLayout
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(HiShellTestTags.CompactPill).assertIsDisplayed()
            composeRule.onNode(hasText("浏览历史列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("返回我的菜单") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("测试帖子标题 7001") and hasAnyAncestor(paneMatcher("正文"))).assertIsDisplayed()
            assertEquals("半折迁移不得重拉详情", detailCallsBeforeFold, harness.threadDetailRepo.loadPageCalls.get())
            assertSame(
                "半折迁移必须保活同一 ThreadDetailViewModel",
                histVm,
                ViewModelProvider(harness.hostViewModel.viewModelStoreOwnerForThread("7001", harness.vmFactory))["thread:7001", ThreadDetailViewModel::class.java],
            )
            assertEquals("半折迁移绝不得跳回论坛", ShellSidebarEntry.History, harness.hostViewModel.state.value.selectedSidebarEntry)

            // 半折铰链双栏按返回：退回「左：我的菜单 | 右：浏览历史」
            composeRule.runOnIdle { assertTrue(harness.hostViewModel.handleBack(ShellPaneLayoutMode.HingeTwoPane)) }
            composeRule.waitForIdle()
            composeRule.onNode(hasText("我的") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("浏览历史列表") and hasAnyAncestor(paneMatcher("正文"))).assertIsDisplayed()

            // 再次展平回三栏：左栏高亮浏览历史、中栏承接浏览历史、右栏安静占位
            layoutFlow.value = flatLayout
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertIsDisplayed()
            composeRule.onNode(hasText("浏览历史列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()

            // ---- 场景 2：Notify 入口 ----
            composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.Notify) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("提醒消息列表 条目 8001").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            val notifyVm = harness.createdDetailVms.last()
            val callsBeforeNotifyFold = harness.threadDetailRepo.loadPageCalls.get()

            layoutFlow.value = hingeLayout
            composeRule.waitForIdle()
            composeRule.onNode(hasText("提醒消息列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("测试帖子标题 8001") and hasAnyAncestor(paneMatcher("正文"))).assertIsDisplayed()
            assertEquals("Notify 半折迁移不得重拉详情", callsBeforeNotifyFold, harness.threadDetailRepo.loadPageCalls.get())
            assertSame(
                notifyVm,
                ViewModelProvider(harness.hostViewModel.viewModelStoreOwnerForThread("8001", harness.vmFactory))["thread:8001", ThreadDetailViewModel::class.java],
            )

            // Notify 铰链双栏按返回：左栏仍留「提醒」，右栏显示提醒专属占位
            composeRule.runOnIdle { assertTrue(harness.hostViewModel.handleBack(ShellPaneLayoutMode.HingeTwoPane)) }
            composeRule.waitForIdle()
            composeRule.onNode(hasText("提醒消息列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNodeWithText("选一条提醒查看对应帖子").assertIsDisplayed()
        }
    }

    /**
     * 闭合 R-E0 R2-S3 反例 A：三栏下切换 `tidA → tidB` 时，详情槽内未显式按 `tid` 加键的
     * `remember` 与 `rememberSaveable` 局部状态必须彻底隔离重置，杜绝跨帖串状态。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun rE0S3_threePaneSwitchTidAToTidB_isolatesUnkeyedRememberAndRememberSaveableLocalState() {
        val hostVm = NextAppHostViewModel()
        hostVm.openThread(ThreadNavArgs("1001", "帖子A", 2))
        val layout = calculateShellPaneLayout(749f, 559f, hasDetailContent = true)

        composeRule.setContent {
            HiTheme {
                val collected by hostVm.state.collectAsStateWithLifecycle()
                MultiPaneReadingContainer(
                    paneLayout = layout,
                    appState = collected,
                    hostViewModel = hostVm,
                    contentPadding = PaddingValues(0.dp),
                    persistedPaneSplit = PaneSplit.DEFAULT,
                    onPaneSplitChange = {},
                    forumContent = { _, _, _ -> Text("列表") },
                    detailContent = { args, _, _, _, _ ->
                        // 故意不给 remember / rememberSaveable 传 tid key，验证容器 key(args.tid)+SaveableStateProvider 隔离
                        var unkeyedMem by remember { mutableIntStateOf(0) }
                        var unkeyedSaveable by rememberSaveable { mutableIntStateOf(0) }
                        Column {
                            Text("当前帖:${args.tid}")
                            Text("mem=$unkeyedMem,save=$unkeyedSaveable")
                            Button(onClick = { unkeyedMem += 1; unkeyedSaveable += 1 }) { Text("局部态+1") }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("当前帖:1001").assertIsDisplayed()
        composeRule.onNodeWithText("mem=0,save=0").assertIsDisplayed()
        repeat(3) {
            composeRule.onNodeWithText("局部态+1").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("mem=3,save=3").assertIsDisplayed()

        // 切至 tidB = 1002：无键局部态必须全部归零，绝不可把 3 串到 1002
        composeRule.runOnIdle { hostVm.openThread(ThreadNavArgs("1002", "帖子B", 2)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前帖:1002").assertIsDisplayed()
        composeRule.onNodeWithText("mem=0,save=0").assertIsDisplayed()
    }

    /**
     * 闭合 R-E0 S4：通过 `StateRestorationTester` 模拟进程被杀重建（全新 `NextAppHostViewModel` 实例），
     * 验证活跃版块、已打开帖子及论坛/详情两份 `LazyListState` 的滚动偏移均无损恢复。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun rE0S4_stateRestorationTester_processDeathRestoresForumThreadAndBothLazyListStates() {
        val restorationTester = StateRestorationTester(composeRule)
        val settingsRepo = RecordingSettingsRepository()
        val authRepo = OfflineAuthRepository()
        val smsFlow = MutableStateFlow(2)
        val notifyFlow = MutableStateFlow(5)
        val listRepo = OfflineThreadListRepository()
        val searchRepo = OfflineSearchRepository()
        val detailRepo = OfflineThreadDetailRepository()
        val gateway = MultiPaneTestHarness().gateway
        val createdHostVms = mutableListOf<NextAppHostViewModel>()

        restorationTester.setContent {
            // 每次进程重建都创建全新 NextAppHostViewModel 与父 ViewModelStoreOwner（内存状态完全清空）
            val hostVm = remember { NextAppHostViewModel(settingsRepo).also(createdHostVms::add) }
            val forumVm = remember { ForumViewModel(listRepo, searchRepo, settingsRepo, authRepo, gateway) }
            val factory = remember(hostVm, forumVm) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = when {
                        modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                        modelClass.isAssignableFrom(net.jejer.hipda.feature.forum.ForumViewModel::class.java) -> forumVm as T
                        modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(detailRepo, gateway) as T
                        else -> throw IllegalArgumentException("Unexpected VM: $modelClass")
                    }
                }
            }
            val owner = remember(factory) {
                object : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
                    override val viewModelStore = androidx.lifecycle.ViewModelStore()
                    override val defaultViewModelProviderFactory = factory
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                NextAppRoot(
                    settingsFlow = settingsRepo.settings,
                    currentAccountFlow = authRepo.currentAccount,
                    smsUnreadCountFlow = smsFlow,
                    notifyUnreadCountFlow = notifyFlow,
                    hostViewModel = hostVm,
                    forumViewModel = forumVm,
                )
            }
        }
        composeRule.waitForIdle()

        // 切至版块 fid=6，滚动论坛列表并打开帖子 6008，再滚动详情列表
        composeRule.onNodeWithTag(HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(6))).performClick()
        composeRule.waitForIdle()
        scrollListInPane("列表", 800f)
        val forumScrollBefore = readListScrollInPane("列表")
        assertTrue("论坛列表应滚离顶部，实际=$forumScrollBefore", forumScrollBefore > 0f)

        composeRule.onNodeWithText("测试帖子标题 6008").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        scrollListInPane("正文", 1000f)
        val detailScrollBefore = readListScrollInPane("正文")
        assertTrue("帖子详情应滚离顶部，实际=$detailScrollBefore", detailScrollBefore > 0f)

        assertEquals(1, createdHostVms.size)

        // 模拟进程被杀与重建
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("进程重建必须生成全新的 NextAppHostViewModel", 2, createdHostVms.size)
        assertNotSame(createdHostVms[0], createdHostVms[1])
        val restoredState = createdHostVms[1].state.value
        assertEquals("应恢复活跃版块 fid=6", ForumListSource.Forum(6), restoredState.activeForumSource)
        assertEquals("应恢复已打开帖子 6008", "6008", restoredState.selectedThread?.tid)

        val forumScrollAfter = readListScrollInPane("列表")
        val detailScrollAfter = readListScrollInPane("正文")
        assertTrue("进程恢复后论坛列表滚动位不得归零，before=$forumScrollBefore after=$forumScrollAfter", forumScrollAfter > 0f)
        assertTrue("进程恢复后帖子详情滚动位不得归零，before=$detailScrollBefore after=$detailScrollAfter", detailScrollAfter > 0f)
        assertEquals("论坛列表滚动位应近似无损恢复", forumScrollBefore, forumScrollAfter, 2f)
        assertEquals("帖子详情滚动位应近似无损恢复", detailScrollBefore, detailScrollAfter, 2f)
    }

    private fun scrollFirstListBy(deltaY: Float) {
        val m = SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config }
        composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, deltaY) }
        composeRule.waitForIdle()
    }

    private fun readFirstListScroll(): Float {
        val m = SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config }
        return composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private fun scrollListInPane(paneTitle: String, deltaY: Float) {
        val m = SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config } and
            hasAnyAncestor(paneMatcher(paneTitle))
        composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, deltaY) }
        composeRule.waitForIdle()
    }

    private fun readListScrollInPane(paneTitle: String): Float {
        val m = SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config } and
            hasAnyAncestor(paneMatcher(paneTitle))
        return composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
}
