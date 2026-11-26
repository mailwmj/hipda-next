package net.jejer.hipda.feature.app

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellDestination
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
 * E3 验收项 2（B4-E3 ② 非论坛入口展平↔半折无损迁移、全宽迁移 0 请求）、验收项 3（R-E0 R2-S3/S4）
 * 与 R-E3 B2（跨一级目的地保留列表/详情滚动位与 0 额外请求、切页过渡期无双份页面/双份玻璃采样源）。
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
     * 闭合 R-E3 B2（单栏 + 三栏）：从论坛滚到中段 → 切到搜索、我的、消息 → 再切回论坛，
     * 断言列表滚动位与详情滚动位均完整保留，且论坛列表请求增量为 0。
     */
    @Test
    @Config(qualifiers = "w749dp-h891dp-xhdpi")
    fun b2_forumScrollSwitchToSearchMineMessagesAndBack_preservesListAndDetailScrollWithZeroExtraRequests() {
        val harness = MultiPaneTestHarness()
        val windowSize = mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
        }
        composeRule.waitForIdle()

        // 1. 单栏 411dp：论坛滚到中段 → 切搜索 → 切我的 → 切消息 → 切回论坛，列表位置不丢、0 额外请求
        scrollFirstListBy(950f)
        val singleScrollBefore = readFirstListScroll()
        assertTrue("单栏论坛应滚离顶部，实际=$singleScrollBefore", singleScrollBefore > 0f)
        val listCallsAfterInitial = harness.threadListRepo.refreshCalls.get()

        listOf(ShellDestination.Search, ShellDestination.Mine, ShellDestination.Messages, ShellDestination.Forum).forEach { dest ->
            composeRule.onNodeWithTag(HiShellTestTags.navItem(dest)).performClick()
            composeRule.waitForIdle()
        }
        assertEquals("单栏跨目的地来回切论坛请求增量必须为 0", listCallsAfterInitial, harness.threadListRepo.refreshCalls.get())
        assertEquals("单栏切回论坛后列表滚动位不得归零", singleScrollBefore, readFirstListScroll(), 2f)

        // 2. 三栏 749dp：论坛保持中段滚动并打开 1008 滚到中段 → 切搜索 → 切浏览历史 → 切提醒 → 切回论坛，列表位置与详情位置不丢
        windowSize.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        val threePaneListBefore = readListScrollInPane("列表")
        assertTrue("三栏论坛应保留非零滚动位，实际=$threePaneListBefore", threePaneListBefore > 0f)

        composeRule.onNodeWithText("测试帖子标题 1008").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        scrollListInPane("正文", 1000f)
        val detailScrollBefore = readListScrollInPane("正文")
        assertTrue("详情应滚离顶部，实际=$detailScrollBefore", detailScrollBefore > 0f)

        listOf(
            ShellSidebarEntry.Search,
            ShellSidebarEntry.History,
            ShellSidebarEntry.Notify,
            ShellSidebarEntry.Forum(2),
        ).forEach { entry ->
            composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(entry) }
            composeRule.waitForIdle()
        }

        assertEquals("三栏跨目的地切回论坛请求增量必须为 0", listCallsAfterInitial, harness.threadListRepo.refreshCalls.get())
        assertEquals("三栏切回论坛后列表滚动位不得丢失", threePaneListBefore, readListScrollInPane("列表"), 2f)

        // 重新打开同一帖子 1008：SaveableStateHolder 保留其详情滚动位置不归零
        composeRule.onNodeWithText("测试帖子标题 1008").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("切回论坛重开 1008 后详情滚动位应保留", detailScrollBefore, readListScrollInPane("正文"), 2f)
    }

    /**
     * 闭合 R-E3 B2（过渡帧单实例与玻璃唯一性）：三栏下 `Search → Forum` 与 `Forum → Search` 交叉淡入淡出每一帧，
     * 严禁双份挂载 `ForumPane` 或双份 `middleGlassState` `LayerBackdrop` / `HiGlassMarker`。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun b2_destinationCrossfade_neverMountsDuplicateForumPaneOrDuplicateGlassSource() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        // 先稳态切至搜索页
        composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.Search) }
        composeRule.waitForIdle()

        fun assertTransitionFramesClean(target: ShellSidebarEntry) {
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(target) }
                repeat(16) { frame ->
                    composeRule.mainClock.advanceTimeByFrame()
                    val forumTitles = composeRule.onAllNodesWithText("Discovery").fetchSemanticsNodes()
                    assertTrue(
                        "R-E3 B2：切至 $target 第 $frame 帧严禁同时挂载两份 ForumPane，实际=${forumTitles.size}",
                        forumTitles.size <= 1,
                    )
                    val glassMarkers = composeRule.onAllNodes(
                        SemanticsMatcher.expectValue(HiGlassMarker, true),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    assertTrue(
                        "R-E3 B2：切至 $target 第 $frame 帧玻璃区域不得双份挂载（≤ 1），实际=${glassMarkers.size}",
                        glassMarkers.size <= 1,
                    )
                    val audit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
                    assertTrue(
                        "R-E3 B2：切至 $target 第 $frame 帧 LayerBackdrop 采样源不得双份挂载（≤ 1），实际=${audit.layerBackdropCount}",
                        audit.layerBackdropCount <= 1,
                    )
                    assertEquals(0, audit.nestedLayerBackdropCount)
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
        }

        // Search -> Forum 过渡逐帧验收
        assertTransitionFramesClean(ShellSidebarEntry.Forum(2))
        // Forum -> Search 过渡逐帧验收
        assertTransitionFramesClean(ShellSidebarEntry.Search)
    }

    /**
     * 闭合 R-BATCH5 B4-E3 ②：通过 `MultiPaneReadingContainer` 真实公开 `secondaryListContent` 槽位验证
     * `History` 与 `Notify` 入口选帖后，展平三栏 ↔ 半折铰链双栏 ↔ 按返回键的无损晋升与回退。
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
                HiTheme {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                            val appState by harness.hostViewModel.state.collectAsStateWithLifecycle()
                            val slotHolder = rememberMultiPaneSlotHolder(harness.hostViewModel)
                            HiAdaptiveShell(
                                selected = appState.selectedDestination,
                                onSelect = harness.hostViewModel::selectDestination,
                                sidebarState = HiSidebarState(selectedEntry = appState.selectedSidebarEntry),
                                onSelectSidebarEntry = harness.hostViewModel::selectSidebarEntry,
                                hasDetailContent = appState.hasDetailContent,
                                managesGlassSource = { true },
                            ) { destination, contentPadding ->
                                MultiPaneReadingContainer(
                                    paneLayout = paneLayout,
                                    appState = appState,
                                    hostViewModel = harness.hostViewModel,
                                    contentPadding = contentPadding,
                                    persistedPaneSplit = PaneSplit.DEFAULT,
                                    onPaneSplitChange = {},
                                    destination = destination,
                                    slotHolder = slotHolder,
                                    shellGlassState = shellGlassState,
                                    forumContent = { chromeSpec, padding, listState ->
                                        ForumPane(
                                            source = appState.activeForumSource,
                                            chromeSpec = chromeSpec,
                                            selectedTid = appState.selectedThread?.tid,
                                            onSelectSource = harness.hostViewModel::selectForumSource,
                                            onOpenThread = harness.hostViewModel::openThread,
                                            onOpenLogin = {},
                                            listState = listState,
                                            contentPadding = padding,
                                            shellContentScope = this,
                                            viewModel = harness.forumViewModel,
                                        )
                                    },
                                    detailContent = { args, chromeSpec, padding, listState, onBack ->
                                        ThreadDetailPane(
                                            args = args,
                                            chromeSpec = chromeSpec,
                                            onBack = onBack,
                                            onOpenLogin = {},
                                            listState = listState,
                                            contentPadding = padding,
                                        )
                                    },
                                    secondaryListContent = { entry, showBack, onBack, padding, listState ->
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
                                                            .clickable { harness.hostViewModel.openThread(ThreadNavArgs(tid, "来自$headerTitle 的帖子 $tid", 2)) }
                                                            .padding(12.dp),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    mineMenuContent = { padding -> Column(Modifier.fillMaxSize().padding(padding)) { Text("我的") } },
                                )
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // ---- 场景 1：History 入口 ----
            composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.History) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("浏览历史列表 条目 7001").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            val histVm = harness.createdDetailVms.last()
            val detailCallsBeforeFold = harness.threadDetailRepo.loadPageCalls.get()

            layoutFlow.value = hingeLayout
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(HiShellTestTags.CompactPill).assertIsDisplayed()
            composeRule.onNode(hasText("浏览历史列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("返回我的菜单") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("测试帖子标题 7001") and hasAnyAncestor(paneMatcher("正文"))).assertIsDisplayed()
            assertEquals("半折迁移不得重拉详情", detailCallsBeforeFold, harness.threadDetailRepo.loadPageCalls.get())
            assertSame(
                histVm,
                ViewModelProvider(harness.hostViewModel.viewModelStoreOwnerForThread("7001", harness.vmFactory))["thread:7001", ThreadDetailViewModel::class.java],
            )
            assertEquals("半折迁移绝不得跳回论坛", ShellSidebarEntry.History, harness.hostViewModel.state.value.selectedSidebarEntry)

            composeRule.runOnIdle { assertTrue(harness.hostViewModel.handleBack(ShellPaneLayoutMode.HingeTwoPane)) }
            composeRule.waitForIdle()
            composeRule.onNode(hasText("我的") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNode(hasText("浏览历史列表") and hasAnyAncestor(paneMatcher("正文"))).assertIsDisplayed()

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

            composeRule.runOnIdle { assertTrue(harness.hostViewModel.handleBack(ShellPaneLayoutMode.HingeTwoPane)) }
            composeRule.waitForIdle()
            composeRule.onNode(hasText("提醒消息列表") and hasAnyAncestor(paneMatcher("列表"))).assertIsDisplayed()
            composeRule.onNodeWithText("选一条提醒查看对应帖子").assertIsDisplayed()
        }
    }

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

        composeRule.runOnIdle { hostVm.openThread(ThreadNavArgs("1002", "帖子B", 2)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前帖:1002").assertIsDisplayed()
        composeRule.onNodeWithText("mem=0,save=0").assertIsDisplayed()
    }

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
            val hostVm = remember { NextAppHostViewModel(settingsRepo).also(createdHostVms::add) }
            val forumVm = remember { ForumViewModel(listRepo, searchRepo, settingsRepo, authRepo, gateway) }
            val factory = remember(hostVm, forumVm) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = when {
                        modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                        modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumVm as T
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

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("进程重建必须生成全新的 NextAppHostViewModel", 2, createdHostVms.size)
        assertNotSame(createdHostVms[0], createdHostVms[1])
        val restoredState = createdHostVms[1].state.value
        assertEquals("应恢复活跃版块 fid=6", ForumListSource.Forum(6), restoredState.activeForumSource)
        assertEquals("应恢复已打开帖子 6008", "6008", restoredState.selectedThread?.tid)

        val forumScrollAfter = readListScrollInPane("列表")
        val detailScrollAfter = readListScrollInPane("正文")
        assertTrue("进程恢复后论坛列表滚动位不得归零", forumScrollAfter > 0f)
        assertTrue("进程恢复后帖子详情滚动位不得归零", detailScrollAfter > 0f)
        assertEquals("论坛列表滚动位应近似无损恢复", forumScrollBefore, forumScrollAfter, 2f)
        assertEquals("帖子详情滚动位应近似无损恢复", detailScrollBefore, detailScrollAfter, 2f)
    }

    /**
     * 闭合 V-E3 F1（阻断回归）：物理铰链双栏处于 `MineMenu | MineSubList(History)` 时，
     * 滚动 `History` 副列表并点击条目使其晋升至左栏、再按真实系统返回键退回右栏、再点一次二次晋升，
     * 全程在真实过渡动画帧内严禁触发 `Key sidebar:History was used multiple times` 崩溃，且副列表滚动位无损保留。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun f1_hingeTwoPane_mineSubListHistory_tapToPromoteAndBackToDemote_noCrashAndPreservesScrollState() {
        val harness = MultiPaneTestHarness()
        val hingeInfo = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        val layoutFlow = MutableStateFlow(WindowLayoutInfo(emptyList()))
        var backDispatcher: OnBackPressedDispatcher? = null

        withWindowLayoutFlow(layoutFlow) {
            composeRule.setContent {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                HiTheme {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                            val appState by harness.hostViewModel.state.collectAsStateWithLifecycle()
                            val slotHolder = rememberMultiPaneSlotHolder(harness.hostViewModel)
                            HiAdaptiveShell(
                                selected = appState.selectedDestination,
                                onSelect = harness.hostViewModel::selectDestination,
                                sidebarState = HiSidebarState(selectedEntry = appState.selectedSidebarEntry),
                                onSelectSidebarEntry = harness.hostViewModel::selectSidebarEntry,
                                hasDetailContent = appState.hasDetailContent,
                                managesGlassSource = { true },
                            ) { destination, contentPadding ->
                                MultiPaneReadingContainer(
                                    paneLayout = paneLayout,
                                    appState = appState,
                                    hostViewModel = harness.hostViewModel,
                                    contentPadding = contentPadding,
                                    persistedPaneSplit = PaneSplit.DEFAULT,
                                    onPaneSplitChange = {},
                                    destination = destination,
                                    slotHolder = slotHolder,
                                    shellGlassState = shellGlassState,
                                    forumContent = { chromeSpec, padding, listState ->
                                        ForumPane(
                                            source = appState.activeForumSource,
                                            chromeSpec = chromeSpec,
                                            selectedTid = appState.selectedThread?.tid,
                                            onSelectSource = harness.hostViewModel::selectForumSource,
                                            onOpenThread = harness.hostViewModel::openThread,
                                            onOpenLogin = {},
                                            listState = listState,
                                            contentPadding = padding,
                                            shellContentScope = this,
                                            viewModel = harness.forumViewModel,
                                        )
                                    },
                                    detailContent = { args, chromeSpec, padding, listState, onBack ->
                                        ThreadDetailPane(
                                            args = args,
                                            chromeSpec = chromeSpec,
                                            onBack = onBack,
                                            onOpenLogin = {},
                                            listState = listState,
                                            contentPadding = padding,
                                        )
                                    },
                                    secondaryListContent = { entry, showBack, onBack, padding, listState ->
                                        Column(Modifier.fillMaxSize().padding(padding)) {
                                            if (showBack && onBack != null) {
                                                Button(onClick = onBack) { Text("返回我的") }
                                            }
                                            Text("副列表:${entry::class.simpleName}")
                                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                                items((1..30).toList()) { idx ->
                                                    val tid = "${9100 + idx}"
                                                    Text(
                                                        text = "历史条目 $tid",
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { harness.hostViewModel.openThread(ThreadNavArgs(tid, "历史帖 $tid", 2)) }
                                                            .padding(vertical = 12.dp, horizontal = 16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    mineMenuContent = { padding ->
                                        Column(Modifier.fillMaxSize().padding(padding)) { Text("我的菜单") }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            val dispatcher = requireNotNull(backDispatcher)

            // 1. 展平三栏进 History 选帖 → 半折晋升 → 返回退回 Left:MineMenu | Right:MineSubList(History)
            composeRule.runOnIdle {
                harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.History)
                harness.hostViewModel.openThread(ThreadNavArgs("9101", "历史帖 9101", 2))
            }
            composeRule.waitForIdle()
            layoutFlow.value = hingeInfo
            composeRule.waitForIdle()
            composeRule.runOnIdle { dispatcher.onBackPressed() }
            composeRule.waitForIdle()

            assertTrue(
                "应处于左栏 MineMenu",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("列表")) and hasText("我的菜单")).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "应处于右栏 MineSubList(History)",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("正文")) and hasText("副列表:History")).fetchSemanticsNodes().isNotEmpty(),
            )

            // 2. 在右栏 MineSubList(History) 滚到中段，记录滚动位后点击条目晋升至左栏（复现原 V-E3 F1 崩溃路径）
            scrollListInPane("正文", 420f)
            val scrollBeforePromote = readListScrollInPane("正文")
            assertTrue("History 副列表应滚离顶部，实际=$scrollBeforePromote", scrollBeforePromote > 0f)

            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.onNodeWithText("历史条目 9110").assertIsDisplayed().performClick()
                repeat(16) { composeRule.mainClock.advanceTimeByFrame() }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()

            assertTrue(
                "V-E3 F1：点条目后 History 必须无崩溃晋升至左栏",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("列表")) and hasText("副列表:History")).fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.onNodeWithText("测试帖子标题 9110").assertIsDisplayed()
            val scrollAfterPromote = readListScrollInPane("列表")
            assertEquals("V-E3 F1：晋升左栏后 History 滚动位必须完整保留", scrollBeforePromote, scrollAfterPromote, 2f)

            // 3. 真实系统返回键退回 Left:MineMenu | Right:MineSubList(History)，同样无崩溃且保留滚动位
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { dispatcher.onBackPressed() }
                repeat(16) { composeRule.mainClock.advanceTimeByFrame() }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()

            assertTrue(
                "回退后左栏应恢复 我的菜单",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("列表")) and hasText("我的菜单")).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "回退后右栏应恢复 History 副列表",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("正文")) and hasText("副列表:History")).fetchSemanticsNodes().isNotEmpty(),
            )
            val scrollAfterDemote = readListScrollInPane("正文")
            assertEquals("V-E3 F1：回退右栏后 History 滚动位必须完整保留", scrollBeforePromote, scrollAfterDemote, 2f)

            // 4. 再次点击另一条目二次晋升，验证多次往返稳定无崩溃
            composeRule.onNodeWithText("历史条目 9111").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertTrue(
                "二次晋升左栏应展示 History",
                composeRule.onAllNodes(hasAnyAncestor(paneMatcher("列表")) and hasText("副列表:History")).fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.onNodeWithText("测试帖子标题 9111").assertIsDisplayed()
        }
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
