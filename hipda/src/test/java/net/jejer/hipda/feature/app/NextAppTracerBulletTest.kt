package net.jejer.hipda.feature.app

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.internal.GeneratedComponentManager
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.di.AppShellBindingModule
import net.jejer.hipda.di.ImageLoaderModule
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NextAppTracerBulletTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 闭合 R-BATCH5 B4-E0：经真实 NextAppRoot + BasicMultiPaneContainer + 标准 LocalViewModelStoreOwner 工厂路径，
     * 在 411dp 将列表与详情均滚至非首项，断言 411dp → 749dp → 411dp 请求增量 == 0、同一 ThreadDetailViewModel 实例未重建、
     * 列表与详情 LazyListState 索引与像素偏移完全不变。
     */
    @Test
    @Config(qualifiers = "w749dp-h891dp-xhdpi")
    fun singlePaneToThreePaneAndBack_preservesViewModelScrollAndZeroExtraRequests() {
        val harness = TestHarness()
        val windowSizeState = mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            ResizableWindowHost(windowSizeState.value) {
                harness.RenderRoot()
            }
        }
        composeRule.waitForIdle()

        assertEquals("首次加载应发出 1 次论坛列表请求", 1, harness.threadListRepo.refreshCalls.get())
        assertEquals("R-D4 S1：必须传入显式非 null sortByPostTime", false, harness.threadListRepo.lastQuery?.sortByPostTime)
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()

        // 通过真实列表滚动至非首项，记录可见标题与顶部像素坐标
        scrollFirstVerticalListBy(900f)
        val forumScrollPosBefore = readFirstVerticalScrollValue()
        assertTrue("列表应滚至非首项，实际 scrollValue=$forumScrollPosBefore", forumScrollPosBefore > 0f)
        val targetTid = "1008"
        composeRule.onAllNodesWithText("测试帖子标题 $targetTid").onFirst().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 单栏进入帖子详情二级页：底部导航胶囊应退场（R-E0 S4 / §3.1 L38）
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertDoesNotExist()
        assertEquals("首次进帖应发出 1 次详情请求", 1, harness.threadDetailRepo.loadPageCalls.get())
        assertEquals("应通过真实工厂路径恰好创建 1 个 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
        val detailVmBefore = harness.createdDetailVms.single()

        scrollFirstVerticalListBy(1200f)
        val detailScrollPosBefore = readFirstVerticalScrollValue()
        assertTrue("详情应滚至非首项，实际 scrollValue=$detailScrollPosBefore", detailScrollPosBefore > 0f)
        val visibleFloorText = findFirstVisibleFloorText(targetTid)
        val visibleFloorTopBefore = composeRule.onNodeWithText(visibleFloorText).fetchSemanticsNode().boundsInRoot.top

        val listCallsSnapshot = harness.threadListRepo.refreshCalls.get()
        val detailCallsSnapshot = harness.threadDetailRepo.loadPageCalls.get()

        // 411dp → 749dp（展开三栏）
        windowSizeState.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertIsDisplayed()
        assertEquals("411→749dp 列表请求增量必须为 0", listCallsSnapshot, harness.threadListRepo.refreshCalls.get())
        assertEquals("411→749dp 详情请求增量必须为 0", detailCallsSnapshot, harness.threadDetailRepo.loadPageCalls.get())
        assertEquals("411→749dp 不得重新创建 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
        val detailVmAt749 = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread(targetTid, harness.vmFactory),
        )["thread:$targetTid", ThreadDetailViewModel::class.java]
        assertSame("411→749dp 必须是同一个 ThreadDetailViewModel 实例", detailVmBefore, detailVmAt749)
        composeRule.onAllNodesWithText("测试帖子标题 $targetTid").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(visibleFloorText).assertIsDisplayed()
        assertEquals(
            "411→749dp 详情顶部像素坐标应保持不变",
            visibleFloorTopBefore,
            composeRule.onNodeWithText(visibleFloorText).fetchSemanticsNode().boundsInRoot.top,
            2f,
        )

        // 749dp → 411dp（合上回单栏：仍处于帖子详情二级页，侧栏消失、底胶囊退场、详情阅读位与实例无损保活）
        windowSizeState.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertDoesNotExist()
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals("749→411dp 列表请求增量必须为 0", listCallsSnapshot, harness.threadListRepo.refreshCalls.get())
        assertEquals("749→411dp 详情请求增量必须为 0", detailCallsSnapshot, harness.threadDetailRepo.loadPageCalls.get())
        assertEquals("749→411dp 不得重新创建 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
        assertEquals("749→411dp 详情滚动位不变", detailScrollPosBefore, readFirstVerticalScrollValue(), 1f)

        // 退出详情返回单栏列表：底胶囊恢复且列表滚动位不变、0 额外请求
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        assertEquals("返回列表请求增量为 0", listCallsSnapshot, harness.threadListRepo.refreshCalls.get())
        assertEquals("返回列表滚动位不变", forumScrollPosBefore, readFirstVerticalScrollValue(), 1f)
    }

    /**
     * 闭合 R-E0 B3（变异 1 捕获：`movableContentOf` 改回 `if/else` 必红）：
     * 经过真实 BasicMultiPaneContainer + 真实 ForumPane / ThreadDetailPane，断言：
     * 1. 411dp → 749dp → 411dp 跨形态搬移时 detailContent 与 forumContent 组合子树 DisposableEffect onDispose == 0 且 remember 实例同一；
     * 2. 单栏进帖 ForumPane 离树再返回时，SaveableStateHolder 完整保住面板内 rememberSaveable 局部状态。
     */
    @Test
    @Config(qualifiers = "w749dp-h891dp-xhdpi")
    fun basicMultiPaneContainer_movesSlotsWithoutDisposingCompositionAndSavesForumState() {
        val harness = TestHarness()
        val windowSizeState = mutableStateOf(DpSize(411.dp, 891.dp))
        val selectedThreadState = mutableStateOf<ThreadNavArgs?>(null)

        var forumDisposedCount = 0
        var detailDisposedCount = 0
        var latestForumToken: Any? = null
        var latestDetailToken: Any? = null
        var forumSaveableCounterValue = 0
        var bumpForumSaveableCounter: (() -> Unit)? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                HiTheme {
                    val size = windowSizeState.value
                    val layout = calculateShellPaneLayout(size.width.value, size.height.value)
                    MultiPaneReadingContainer(
                        paneLayout = layout,
                        appState = NextAppState(selectedThread = selectedThreadState.value),
                        hostViewModel = harness.hostViewModel,
                        contentPadding = PaddingValues(0.dp),
                        persistedPaneSplit = PaneSplit.DEFAULT,
                        onPaneSplitChange = {},
                        forumContent = { chromeSpec, padding, _ ->
                            val token = remember { Any() }
                            latestForumToken = token
                            val savedCounter = rememberSaveable { mutableIntStateOf(0) }
                            forumSaveableCounterValue = savedCounter.intValue
                            bumpForumSaveableCounter = { savedCounter.intValue += 1 }
                            DisposableEffect(Unit) { onDispose { forumDisposedCount += 1 } }
                            ForumPane(
                                source = ForumListSource.Forum(2),
                                chromeSpec = chromeSpec,
                                selectedTid = selectedThreadState.value?.tid,
                                onSelectSource = {},
                                onOpenThread = { selectedThreadState.value = it },
                                onOpenLogin = {},
                                listState = rememberLazyListState(),
                                contentPadding = padding,
                                viewModel = harness.forumViewModel,
                            )
                        },
                        detailContent = { args, chromeSpec, padding, _, _ ->
                            val token = remember(args.tid) { Any() }
                            latestDetailToken = token
                            DisposableEffect(args.tid) { onDispose { detailDisposedCount += 1 } }
                            ThreadDetailPane(
                                args = args,
                                chromeSpec = chromeSpec,
                                onBack = { selectedThreadState.value = null },
                                onOpenLogin = {},
                                listState = rememberLazyListState(),
                                contentPadding = padding,
                            )
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 单栏 411dp 修改 ForumPane 内部 rememberSaveable 状态，进帖离树后再返回，断言状态保留
        composeRule.runOnIdle { repeat(7) { bumpForumSaveableCounter?.invoke() } }
        composeRule.waitForIdle()
        assertEquals(7, forumSaveableCounterValue)

        selectedThreadState.value = ThreadNavArgs(tid = "1003", title = "测试帖子标题 1003", fid = 2)
        composeRule.waitForIdle()
        val detailTokenAt411 = requireNotNull(latestDetailToken)
        assertEquals("单栏首次进入详情不应触发 detail onDispose", 0, detailDisposedCount)

        // 2. 411dp → 749dp → 411dp：detailMovable 原地搬移，onDispose 必须为 0 且 remember 实例同一
        windowSizeState.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        assertEquals("411→749dp detailMovable 必须原地搬移不得 dispose", 0, detailDisposedCount)
        assertSame("411→749dp detail 内部 remember 实例必须同一", detailTokenAt411, latestDetailToken)
        assertEquals("重新挂回列表时 SaveableStateHolder 必须恢复局部 rememberSaveable 状态", 7, forumSaveableCounterValue)

        val forumTokenAt749 = requireNotNull(latestForumToken)
        val forumDisposeBeforeCollapse = forumDisposedCount

        // 3. 749dp → 411dp（保持选中帖子）：detailMovable 再次原地搬移
        windowSizeState.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        assertEquals("749→411dp detailMovable 必须原地搬移不得 dispose", 0, detailDisposedCount)
        assertSame("749→411dp detail 内部 remember 实例必须同一", detailTokenAt411, latestDetailToken)

        // 4. 在 411dp 关闭帖子返回列表，SaveableStateHolder 再次恢复局部状态；展开至 749dp 列表原地搬移不 dispose
        selectedThreadState.value = null
        composeRule.waitForIdle()
        assertEquals("单栏返回列表后 rememberSaveable 局部状态必须保留", 7, forumSaveableCounterValue)
        val forumTokenBeforeExpand = requireNotNull(latestForumToken)
        val forumDisposeBeforeExpand = forumDisposedCount

        windowSizeState.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        assertEquals("无详情时 411→749dp forumMovable 必须原地搬移不得 dispose", forumDisposeBeforeExpand, forumDisposedCount)
        assertSame("无详情时 411→749dp forum 内部 remember 实例必须同一", forumTokenBeforeExpand, latestForumToken)
        assertNotNull(forumTokenAt749)
        assertTrue(forumDisposeBeforeCollapse >= 1)
    }

    /**
     * 负责人补充点 2：单栏进帖后 ForumPane 离开组合树，返回列表后首项索引和偏移不变、请求增量为 0，且已关帖 Store 立即 clear()。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun singlePaneEnterThreadAndBack_preservesForumScrollOffsetWithZeroExtraRequests() {
        val harness = TestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        scrollFirstVerticalListBy(1200f)
        val scrollBefore = readFirstVerticalScrollValue()
        val callsBefore = harness.threadListRepo.refreshCalls.get()
        assertTrue("列表应处于非首项位置，实际 scroll=$scrollBefore", scrollBefore > 0f)

        composeRule.onNodeWithText("测试帖子标题 1010").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertDoesNotExist()

        var cleared1010 = false
        harness.createdDetailVms.single().addCloseable(Closeable { cleared1010 = true })

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()

        assertTrue("返回列表后已关闭帖子的 Store 应立即 clear()", cleared1010)
        assertEquals("返回列表后请求增量必须为 0", callsBefore, harness.threadListRepo.refreshCalls.get())
        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        assertEquals("返回列表后滚动位置不变", scrollBefore, readFirstVerticalScrollValue(), 1f)
    }

    /**
     * 三栏切换新 tid 与清空右栏时，旧 tid 的 ViewModelStore 立即调用 clear()。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun threePaneSwitchTidAndClearDetail_clearsOldThreadViewModelStore() {
        val harness = TestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        var cleared1001 = false
        val vm1001 = harness.createdDetailVms.last()
        vm1001.addCloseable(Closeable { cleared1001 = true })

        composeRule.onNodeWithText("测试帖子标题 1002").performClick()
        composeRule.waitForIdle()

        assertTrue("切换新 tid 后旧 tid=1001 的 ViewModelStore 必须立即 clear()", cleared1001)
        val vm1002 = harness.createdDetailVms.last()
        assertNotSame(vm1001, vm1002)

        var cleared1002 = false
        vm1002.addCloseable(Closeable { cleared1002 = true })

        composeRule.runOnIdle { harness.hostViewModel.closeThread() }
        composeRule.waitForIdle()

        assertTrue("清空右栏后 tid=1002 的 ViewModelStore 必须立即 clear()", cleared1002)
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }

    /**
     * 闭合 R-E0 B1：宽屏（840dp 左栏）与窄屏（411dp 标签行）下，版块 ↔ 今日新帖来回切换时，
     * NextAppState.activeForumSource、selectedSidebarEntry 左栏高亮与列表实际查询源始终完全一致。
     */
    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun forumAndTodayNewNavigation_staysConsistentAcrossWideSidebarAndNarrowTabs() {
        val harness = TestHarness()
        val windowSizeState = mutableStateOf(DpSize(840.dp, 900.dp))
        val secondForumName = ForumMetadataRegistry.resolve(6).fullName

        composeRule.setContent {
            ResizableWindowHost(windowSizeState.value) {
                harness.RenderRoot()
            }
        }
        composeRule.waitForIdle()

        // 1. 初始宽屏：默认首关注版块 fid=2
        assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.Forum(2), harness.hostViewModel.state.value.selectedSidebarEntry)
        assertEquals(1, harness.threadListRepo.refreshCalls.get())
        assertEquals(0, harness.searchRepo.newPostsCalls.get())

        // 2. 宽屏点击左栏「今日新帖」：列表必须切到 SearchRepository.getNewPosts，不能停在旧版块
        composeRule.onNode(
            hasText("今日新帖") and hasAnyAncestor(hasTestTag(HiShellTestTags.NavigationSidebar)),
        ).performClick()
        composeRule.waitForIdle()

        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, harness.hostViewModel.state.value.selectedSidebarEntry)
        assertEquals("点击今日新帖必须触发 SearchRepository.getNewPosts", 1, harness.searchRepo.newPostsCalls.get())
        composeRule.onNodeWithText("今日新帖测试标题 9001").assertIsDisplayed()

        // 3. 缩窄至 411dp 单栏：今日新帖标签选中、列表来源与侧栏高亮保持一致
        windowSizeState.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()

        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, harness.hostViewModel.state.value.selectedSidebarEntry)
        composeRule.onAllNodesWithText("今日新帖")[1].assertIsSelected()
        composeRule.onNodeWithText("今日新帖测试标题 9001").assertIsDisplayed()

        // 4. 窄屏切去「消息」再切回「论坛」：不得把 TodayNew 错映回 Forum(fid)
        composeRule.onNode(hasText("消息") and hasAnyAncestor(hasTestTag(HiShellTestTags.BottomPill))).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("论坛") and hasAnyAncestor(hasTestTag(HiShellTestTags.BottomPill))).performClick()
        composeRule.waitForIdle()
        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, harness.hostViewModel.state.value.selectedSidebarEntry)

        // 5. 窄屏点击第二版块标签（fid=6）：列表与左栏高亮同步切到 Forum(6)
        composeRule.onNodeWithText(secondForumName).performClick()
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.Forum(6), harness.hostViewModel.state.value.selectedSidebarEntry)
        assertEquals(6, harness.threadListRepo.lastQuery?.fid)
        composeRule.onNodeWithText("测试帖子标题 6001").assertIsDisplayed()

        // 6. 窄屏切回「今日新帖」标签：selectedSidebarEntry 必须是 TodayNew，不能硬编码 Forum(fid)
        composeRule.onNodeWithText("今日新帖").performClick()
        composeRule.waitForIdle()
        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, harness.hostViewModel.state.value.selectedSidebarEntry)
        composeRule.onNodeWithText("今日新帖测试标题 9001").assertIsDisplayed()

        // 7. 展开回宽屏 840dp：左栏高亮仍是 TodayNew；点击左栏版块 2 切回 Forum(2)
        windowSizeState.value = DpSize(840.dp, 900.dp)
        composeRule.waitForIdle()
        assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, harness.hostViewModel.state.value.selectedSidebarEntry)

        composeRule.onNode(
            hasText(ForumMetadataRegistry.resolve(2).fullName) and hasAnyAncestor(hasTestTag(HiShellTestTags.NavigationSidebar)),
        ).performClick()
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.Forum(2), harness.hostViewModel.state.value.selectedSidebarEntry)
        composeRule.onNodeWithText("测试帖子标题 1001").assertIsDisplayed()
    }

    /**
     * 闭合 R-E0 B2：带 actionLabel 的 Snackbar 显式使用有限时长 Long（10s），绝不为 Indefinite；
     * 连发两条带动作的提示时，第一条到时自动消失，第二条正常显示并到时消失。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun snackbarWithActionLabel_usesFiniteLongDurationAndShowsQueuedSecondSnackbar() {
        val harness = TestHarness()
        var capturedController: PaneSnackbarController? = null
        var capturedScope: CoroutineScope? = null
        val renderer = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                capturedController = LocalPaneSnackbarController.current
                capturedScope = rememberCoroutineScope()
            }
        }

        composeRule.setContent { harness.RenderRoot(loginOverlayRenderer = renderer) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()

        val controller = requireNotNull(capturedController)
        val scope = requireNotNull(capturedScope)

        composeRule.mainClock.autoAdvance = false
        val firstJob = scope.launch {
            controller.show(message = "已收藏", target = PaneSnackbarTarget.DetailPane, actionLabel = "撤销")
        }
        val secondJob = scope.launch {
            controller.show(message = "已关注", target = PaneSnackbarTarget.ContentArea, actionLabel = "撤销")
        }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithText("已收藏").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").assertIsDisplayed()

        // 5.5 秒时仍在（验证是 Long=10s 而非 Short=4s）
        composeRule.mainClock.advanceTimeBy(5_000L)
        assertFalse("5.5 秒时 Long 时长 Snackbar 不应提前消失", firstJob.isCompleted)
        composeRule.onNodeWithText("已收藏").assertIsDisplayed()

        // 推进超过 10s：第一条自动消失且不锁死，第二条带动作提示正常展示
        composeRule.mainClock.advanceTimeBy(5_500L)
        assertTrue("第一条带动作 Snackbar 到时必须自动结束，不得 Indefinite 占锁", firstJob.isCompleted)
        composeRule.onNodeWithText("已关注").assertIsDisplayed()

        // 再推进超过 10s：第二条亦自动消失
        composeRule.mainClock.advanceTimeBy(11_000L)
        assertTrue("第二条带动作 Snackbar 到时亦应自动结束", secondJob.isCompleted)
    }

    /**
     * 闭合负责人补充点 1 与 R-E0 B3（变异 3 捕获：旋转重建 Store 必红）：
     * RotationHarnessActivity 真实挂载 NextAppRoot → BasicMultiPaneContainer → ThreadDetailPane，
     * 通过标准 ComponentActivity.defaultViewModelProviderFactory 解析 ThreadDetailViewModel；
     * 真实触发 configurationChange(竖屏→横屏) 重建 Activity 后，验证同一 ThreadDetailViewModel 实例存活、
     * 工厂未被二次调用、loadPage 增量为 0、SavedStateHandleSupport 与 findHiltComponentActivity 路径有效。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun activityRotation_retainsHostStoreMapAndSameThreadDetailViewModelInstance() {
        val harness = TestHarness()
        RotationHarnessActivity.activeHarness = harness
        RotationHarnessActivity.composedActivities.clear()

        val controller = Robolectric.buildActivity(RotationHarnessActivity::class.java).setup()
        try {
            val activityBefore = controller.get()
            pumpComposeFrames()
            assertTrue("旋转前 Activity 应完成首次 Compose", activityBefore in RotationHarnessActivity.composedActivities)

            val hostBefore = ViewModelProvider(activityBefore)[NextAppHostViewModel::class.java]
            hostBefore.openThread(ThreadNavArgs(tid = "2048", title = "旋转保活帖", fid = 2))
            pumpComposeFrames()

            assertEquals("旋转前应通过真实 BasicMultiPaneContainer 创建 1 个 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
            val vmBefore = harness.createdDetailVms.single()
            assertEquals("首次进帖应触发 1 次 loadPage", 1, harness.threadDetailRepo.loadPageCalls.get())

            // 验证 owner 实现 HasDefaultViewModelProviderFactory 且其 CreationExtras 满足 Dagger HiltViewModelFactory 的 SavedStateHandle 创建条件
            val ownerBefore: ViewModelStoreOwner = hostBefore.viewModelStoreOwnerForThread(
                tid = "2048",
                defaultViewModelProviderFactory = activityBefore.defaultViewModelProviderFactory,
                defaultViewModelCreationExtras = activityBefore.defaultViewModelCreationExtras,
            )
            assertTrue(ownerBefore is HasDefaultViewModelProviderFactory)
            val extrasBefore = (ownerBefore as HasDefaultViewModelProviderFactory).defaultViewModelCreationExtras
            val savedStateHandle = MutableCreationExtras(extrasBefore).apply {
                set(ViewModelProvider.NewInstanceFactory.VIEW_MODEL_KEY, "thread:2048")
            }.createSavedStateHandle()
            assertNotNull("Scoped owner 的 CreationExtras 必须可供 Hilt SavedStateHandleSupport 使用", savedStateHandle)

            // 验证 findHiltComponentActivity 对非 Hilt Activity 回退 null、对带 ContextWrapper 的 Hilt Activity 精确解包
            assertNull(activityBefore.findHiltComponentActivity())
            val fakeHiltActivity = FakeHiltComponentActivity()
            assertSame(fakeHiltActivity, ContextWrapper(fakeHiltActivity).findHiltComponentActivity())

            // 触发真实 Android 配置变化（竖屏 → 横屏三栏，Activity 销毁并重建、重新执行 setContent { NextAppRoot(...) }）
            val rotatedConfig = Configuration(activityBefore.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 749
                screenHeightDp = 559
            }
            controller.configurationChange(rotatedConfig)
            val activityAfter = controller.get()
            if (!activityAfter.window.decorView.isAttachedToWindow) {
                controller.visible()
            }
            pumpComposeFrames()

            assertNotSame("旋转后 Activity 实例应已重建", activityBefore, activityAfter)
            assertTrue("旋转后新 Activity 必须真实完成 NextAppRoot Compose", activityAfter in RotationHarnessActivity.composedActivities)

            val hostAfter = ViewModelProvider(activityAfter)[NextAppHostViewModel::class.java]
            assertSame("旋转后 NextAppHostViewModel 应存活且为同一实例", hostBefore, hostAfter)
            assertEquals("2048", hostAfter.state.value.selectedThread?.tid)
            assertEquals("旋转后 BasicMultiPaneContainer 必须复用旧 Store，不得二次创建 ThreadDetailViewModel", 1, harness.createdDetailVms.size)

            val ownerAfter = hostAfter.viewModelStoreOwnerForThread(
                tid = "2048",
                defaultViewModelProviderFactory = activityAfter.defaultViewModelProviderFactory,
                defaultViewModelCreationExtras = activityAfter.defaultViewModelCreationExtras,
            )
            assertSame("旋转后应复用同一个 tid ViewModelStore", ownerBefore.viewModelStore, ownerAfter.viewModelStore)
            val vmAfter = ViewModelProvider(ownerAfter)["thread:2048", ThreadDetailViewModel::class.java]
            assertSame("旋转/配置变化后必须是同一个 ThreadDetailViewModel 实例", vmBefore, vmAfter)
            assertEquals("旋转后不应重拉 loadPage", 1, harness.threadDetailRepo.loadPageCalls.get())
        } finally {
            controller.destroy()
            RotationHarnessActivity.activeHarness = null
            RotationHarnessActivity.composedActivities.clear()
        }
    }

    /**
     * 闭合 R-E0 B3（漏洞 ③）：完全去除旁路 HiTheme，直接从 NextAppRoot 内部真实 HiTheme 渲染树与正文 TextLayoutResult
     * 断言实时深色/纯黑/字号切换生效，且不重建 ThreadDetailViewModel、不丢失阅读位置、不重拉网络。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun liveThemeAndFontSwitch_updatesRealNextAppRootHiThemeWithZeroReloads() {
        val harness = TestHarness()
        var observedDark = false
        var observedPureBlack = false
        var observedFontStep = HiFontSizeStep.DEFAULT
        val themeProbeRenderer = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                observedDark = HiTheme.state.darkTheme
                observedPureBlack = HiTheme.state.pureBlack
                observedFontStep = HiTheme.typography.fontSizeStep
            }
        }

        composeRule.setContent { harness.RenderRoot(loginOverlayRenderer = themeProbeRenderer) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 校验切换前 NextAppRoot 内真实正文排版字号为默认 STEP_3 (16sp)
        val bodyNodeBefore = composeRule.onNodeWithText("帖子 1001 第 1 楼正文内容，用于验证滚动位置与实例保活。").fetchSemanticsNode()
        val layoutsBefore = mutableListOf<TextLayoutResult>()
        bodyNodeBefore.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layoutsBefore)
        assertEquals("默认字号 STEP_3 正文应为 16sp", 16.sp, layoutsBefore.first().layoutInput.style.fontSize)

        scrollFirstVerticalListBy(1100f)
        val detailScrollBefore = readFirstVerticalScrollValue()
        assertTrue("切主题前详情应处于非首项位置，实际 scroll=$detailScrollBefore", detailScrollBefore > 0f)
        val visibleFloorText = findFirstVisibleFloorText("1001")

        val vmBefore = harness.createdDetailVms.single()
        val listCallsBefore = harness.threadListRepo.refreshCalls.get()
        val detailCallsBefore = harness.threadDetailRepo.loadPageCalls.get()

        // 实时切换深色 + 纯黑 + 第 5 档字号 (18sp)，并在 NextAppRoot 内部真实 HiTheme 子树中采集状态
        harness.settingsRepo.updateSettings(AppSettings(themeMode = ThemeMode.DARK, pureBlack = true, fontStep = 4))
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.AccountMenu) }
        composeRule.waitForIdle()

        assertTrue("NextAppRoot 真实 HiTheme 深色模式应实时生效", observedDark)
        assertTrue("NextAppRoot 真实 HiTheme 纯黑模式应实时生效", observedPureBlack)
        assertEquals("NextAppRoot 真实 HiTheme 第 5 档字号应实时生效", HiFontSizeStep.STEP_5, observedFontStep)

        val bodyNodeAfter = composeRule.onNodeWithText(visibleFloorText).fetchSemanticsNode()
        val layoutsAfter = mutableListOf<TextLayoutResult>()
        bodyNodeAfter.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layoutsAfter)
        assertEquals("切换第 5 档字号后真实正文排版应更新为 18sp", 18.sp, layoutsAfter.first().layoutInput.style.fontSize)

        assertEquals("切主题不应丢失详情滚动位置", detailScrollBefore, readFirstVerticalScrollValue(), 2f)
        assertEquals("切主题列表请求增量为 0", listCallsBefore, harness.threadListRepo.refreshCalls.get())
        assertEquals("切主题详情请求增量为 0", detailCallsBefore, harness.threadDetailRepo.loadPageCalls.get())
        assertEquals("切主题不得重建 ThreadDetailViewModel", 1, harness.createdDetailVms.size)
        assertSame("切主题后必须是同一个 ThreadDetailViewModel", vmBefore, harness.createdDetailVms.single())
    }

    /**
     * 闭合 R-E0 B3（变异 2 捕获：删掉 `managesGlassSource` 必红）+ S1（窄屏「我的」已登录账号菜单入口）+ Coil 3 / @BindsOptionalOf 校验：
     * 1. 首屏骨架态（LazyColumn 未挂载）下断言 ContentContainer 整棵子树 0 个 LayerBackdrop，锁死 managesGlassSource；
     * 2. 数据到达后断言唯一 LayerBackdrop 仅挂在 ForumPane LazyColumn 上且玻璃面数不超预算；
     * 3. 窄屏已登录态切换「我的」页，点击「账号」按钮触发 LoginOverlayMode.AccountMenu；
     * 4. 校验 AppShellBindingModule @BindsOptionalOf 与 ImageLoaderModule 500MB 非阻塞构建。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun singlePaneGlassTopology_managesGlassSourceMineAccountAndImageLoader_areWiredProperly() {
        val refreshGate = CompletableDeferred<Unit>()
        val harness = TestHarness(refreshGate = refreshGate)
        val renderedModes = mutableListOf<LoginOverlayMode>()
        val renderer = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                LaunchedEffect(mode) { renderedModes += mode }
                Text("登录插槽:${mode.name}")
            }
        }

        composeRule.setContent { harness.RenderRoot(loginOverlayRenderer = renderer) }
        composeRule.waitForIdle()

        // 1. 骨架态（LazyColumn 未挂载）：若删掉 managesGlassSource，ShellDestinationHost 会挂回退 LayerBackdrop 导致此处立即变红
        val loadingContainer = composeRule.onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode()
        assertEquals(
            "论坛页首屏骨架态下 ContentContainer 整棵子树不得挂载任何回退 LayerBackdrop（必须由 managesGlassSource 接管）",
            0,
            countLayerBackdrops(loadingContainer.layoutInfo),
        )

        // 2. 完成首屏加载后：唯一 LayerBackdrop 挂在 ForumPane LazyColumn 上
        refreshGate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1001").assertIsDisplayed()

        val loadedContainer = composeRule.onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode()
        assertEquals("加载后仅 ForumPane LazyColumn 挂载 1 处 LayerBackdrop", 1, countLayerBackdrops(loadedContainer.layoutInfo))
        val scrollNodes = composeRule.onAllNodes(
            SemanticsMatcher("hasVerticalScrollAxisRange") { SemanticsProperties.VerticalScrollAxisRange in it.config },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(
            "ForumPane 的 LazyColumn 必须挂载 shellGlassSource()（LayerBackdrop）",
            scrollNodes.any { node ->
                node.layoutInfo.getModifierInfo().any { it.modifier.toString().contains("LayerBackdrop", ignoreCase = true) }
            },
        )
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

        // 3. S1：窄屏已登录态进入「我的」占位页，必须有「账号」入口触发 AccountMenu（供 E15 退出登录）
        composeRule.onNode(hasText("我的") and hasAnyAncestor(hasTestTag(HiShellTestTags.BottomPill))).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("账号").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(LoginOverlayMode.AccountMenu), renderedModes)
        composeRule.onNodeWithText("登录插槽:AccountMenu").assertIsDisplayed()

        // 4. 校验 AppShellBindingModule @BindsOptionalOf 与 ImageLoaderModule 极薄非阻塞构建
        assertNotNull(AppShellBindingModule::class.java.getAnnotation(Module::class.java))
        val optionalMethod = AppShellBindingModule::class.java.getDeclaredMethod("bindOptionalLoginOverlayRenderer")
        assertNotNull(optionalMethod.getAnnotation(BindsOptionalOf::class.java))

        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = ImageLoaderModule.provideImageLoader(context, OkHttpClient())
        assertNotNull(loader.diskCache)
        assertEquals(500L * 1024L * 1024L, ImageLoaderModule.DEFAULT_DISK_CACHE_MAX_BYTES)
        loader.shutdown()
    }

    // ---- Test Helpers & Offline Fakes ----

    class TestHarness(refreshGate: CompletableDeferred<Unit>? = null) {
        val settingsRepo = FakeSettingsRepository()
        val authRepo = FakeAuthRepository()
        val smsUnread = MutableStateFlow(2)
        val notifyUnread = MutableStateFlow(5)
        val smsUnreadFlow = smsUnread.asStateFlow()
        val notifyUnreadFlow = notifyUnread.asStateFlow()
        val gateway = createIdleGateway()
        val threadListRepo = FakeThreadListRepository(refreshGate)
        val searchRepo = FakeSearchRepository()
        val threadDetailRepo = FakeThreadDetailRepository()
        val hostViewModel = NextAppHostViewModel()
        val forumViewModel = ForumViewModel(threadListRepo, searchRepo, settingsRepo, authRepo, gateway)
        val createdDetailVms = mutableListOf<ThreadDetailViewModel>()

        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostViewModel as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(threadDetailRepo, gateway).also(createdDetailVms::add) as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }

        val parentOwner: ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
        }

        @Composable
        fun RenderRoot(loginOverlayRenderer: LoginOverlayRenderer? = null) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = settingsRepo.settings,
                    currentAccountFlow = authRepo.currentAccount,
                    smsUnreadCountFlow = smsUnreadFlow,
                    notifyUnreadCountFlow = notifyUnreadFlow,
                    loginOverlayRenderer = loginOverlayRenderer,
                    hostViewModel = hostViewModel,
                    forumViewModel = forumViewModel,
                )
            }
        }
    }

    private fun scrollFirstVerticalListBy(deltaY: Float) {
        val scrollMatcher = SemanticsMatcher("hasVerticalScrollAxisRange") {
            SemanticsProperties.VerticalScrollAxisRange in it.config
        }
        composeRule.onAllNodes(scrollMatcher, useUnmergedTree = true).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, deltaY) }
        composeRule.waitForIdle()
    }

    private fun readFirstVerticalScrollValue(): Float {
        val scrollMatcher = SemanticsMatcher("hasVerticalScrollAxisRange") {
            SemanticsProperties.VerticalScrollAxisRange in it.config
        }
        return composeRule.onAllNodes(scrollMatcher, useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private fun findFirstVisibleFloorText(tid: String): String {
        val prefix = "帖子 $tid 第 "
        return composeRule.onAllNodes(
            SemanticsMatcher("floorBody") { node ->
                node.config.contains(SemanticsProperties.Text) &&
                    node.config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().config[SemanticsProperties.Text].first { it.text.startsWith(prefix) }.text
    }

    private fun countLayerBackdrops(root: LayoutInfo): Int {
        var visited = 0
        fun walk(node: LayoutInfo): Int {
            visited += 1
            var count = node.getModifierInfo().count { it.modifier.javaClass.name.contains("LayerBackdrop", ignoreCase = true) }
            val getter = node.javaClass.methods.firstOrNull {
                (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
            }
            val children = (getter?.invoke(node) as? List<*>).orEmpty()
            for (child in children) {
                if (child is LayoutInfo) count += walk(child)
            }
            return count
        }
        val total = walk(root)
        assertTrue("LayoutNode 子树遍历必须覆盖真实子节点，实际 visited=$visited", visited > 5)
        return total
    }

    private fun pumpComposeFrames() {
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    @Composable
    private fun ResizableWindowHost(windowSize: DpSize, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val base = LocalWindowInfo.current
        val fakeWindowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = base.isWindowFocused
            override val containerDpSize: DpSize get() = windowSize
            override val containerSize: IntSize
                get() = with(density) { IntSize(windowSize.width.toPx().roundToInt(), windowSize.height.toPx().roundToInt()) }
        }
        CompositionLocalProvider(LocalWindowInfo provides fakeWindowInfo) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val settingsFlow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = settingsFlow
        fun updateSettings(next: AppSettings) { settingsFlow.value = next }
        override suspend fun setThemeMode(themeMode: ThemeMode) = settingsFlow.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = settingsFlow.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = settingsFlow.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = settingsFlow.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = settingsFlow.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = settingsFlow.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = settingsFlow.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) =
            settingsFlow.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
        override suspend fun setTailEnabled(enabled: Boolean) = Unit
        override suspend fun setTailText(text: String) = Unit
        override suspend fun setTailUrl(url: String) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
        override suspend fun setSilentEndMinutes(minutes: Int) = Unit
        override suspend fun setForumServer(server: String) = Unit
        override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
    }

    class FakeAuthRepository : AuthRepository {
        private val _account = MutableStateFlow<SavedAccount?>(
            SavedAccount(uid = "42", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 1L),
        )
        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(_account.value))
        override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn(_account.value!!))
        override val sessionEpoch: Long = 1L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult =
            LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("offline")
        override suspend fun logout() { _account.value = null }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult = SilentReloginResult.NotLoggedIn
    }

    class FakeSearchRepository : SearchRepository {
        val newPostsCalls = AtomicInteger(0)
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(
            query: String,
            author: String,
            forumId: Int?,
            fullText: Boolean,
            page: Int,
            searchId: String?,
            priority: AccessPriority,
            recordHistory: Boolean,
        ): SearchResult = getNewPosts(page, searchId, priority)

        override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult {
            newPostsCalls.incrementAndGet()
            val items = (1..5).map { idx ->
                val tid = (9000 + idx).toString()
                SearchItem(tid, null, "今日新帖测试标题 $tid", null, "新帖作者$idx", idx.toString(), null, "刚刚", "Discovery")
            }
            return SearchResult.Success(page = page, fullText = false, searchPage = SearchPage("sid-1", 2, items, emptyPageMeta()))
        }

        override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
        override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
        override suspend fun recordSearchHistory(query: String): List<String> = emptyList()
        override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
        override suspend fun restoreSearchHistory(query: String, index: Int): List<String> = emptyList()
        override suspend fun clearSearchHistory() = Unit
        override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
    }

    class FakeThreadListRepository(
        private val refreshGate: CompletableDeferred<Unit>? = null,
    ) : ThreadListRepository {
        val refreshCalls = AtomicInteger(0)
        val loadMoreCalls = AtomicInteger(0)
        @Volatile var lastQuery: ThreadListQuery? = null
        private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()

        private fun stateFor(query: ThreadListQuery): MutableStateFlow<ThreadListLoadState> =
            states.getOrPut(query) { MutableStateFlow(ThreadListLoadState(query = query)) }

        override suspend fun loadPage(
            query: ThreadListQuery,
            startPage: Int,
            existingTids: Set<String>,
            minVisibleItems: Int,
            maxAutoBackfillPages: Int,
            priority: AccessPriority,
        ): ThreadListLoadResult = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

        override suspend fun refresh(
            query: ThreadListQuery,
            minVisibleItems: Int,
            maxAutoBackfillPages: Int,
            priority: AccessPriority,
        ): ThreadListLoadResult {
            refreshCalls.incrementAndGet()
            lastQuery = query
            stateFor(query).update { it.copy(isLoading = true) }
            refreshGate?.await()
            val baseTid = if (query.fid == 2) 1000 else query.fid * 1000
            val summaries = (1..30).map { idx ->
                val tid = (baseTid + idx).toString()
                ThreadSummary(
                    tid = tid,
                    title = "测试帖子标题 $tid",
                    titleColor = null,
                    typeName = null,
                    isSticky = false,
                    isNew = false,
                    isPoll = false,
                    author = "作者$idx",
                    authorId = idx.toString(),
                    avatarUrl = null,
                    timeCreate = "2026-11-25",
                    timeUpdate = "刚刚",
                    lastPostAuthor = "回复者$idx",
                    replyCountText = "${idx * 3}",
                    viewCountText = "${idx * 20}",
                    withPic = false,
                    maxPage = 2,
                )
            }
            val items = summaries.map { ThreadListItem(summary = it, isVisited = false) }
            stateFor(query).value = ThreadListLoadState(
                query = query,
                items = items,
                fetchedPage = 1,
                lastPage = 3,
                hasMore = true,
                pagesFetchedInBatch = 1,
                meta = emptyPageMeta(),
                isLoading = false,
            )
            return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 3, true, 1, emptyPageMeta())
        }

        override suspend fun loadMore(
            query: ThreadListQuery,
            minVisibleItems: Int,
            maxAutoBackfillPages: Int,
            priority: AccessPriority,
        ): ThreadListLoadResult {
            loadMoreCalls.incrementAndGet()
            return refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
        }

        override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
        override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>): Flow<List<ThreadListItem>> =
            rawThreads.map { list -> list.map { ThreadListItem(it, false) } }
    }

    class FakeThreadDetailRepository : ThreadDetailRepository {
        val loadPageCalls = AtomicInteger(0)

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadPageCalls.incrementAndGet()
            val floors = (1..30).map { idx ->
                PostFloor(
                    postId = "${tid}_$idx",
                    floor = idx,
                    page = page,
                    uid = idx.toString(),
                    author = "楼层作者$idx",
                    avatarUrl = null,
                    timePost = "2026-11-25 10:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("帖子 $tid 第 $idx 楼正文内容，用于验证滚动位置与实例保活。")))),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "测试帖子标题 $tid", page, 3, floors, 30, emptyPageMeta(), true),
                filter = filter,
                forumName = forumName,
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
    }

    companion object {
        fun emptyPageMeta(): DiscuzPageMeta = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "42", username = "HiUser"),
            formhash = "abc12345",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        fun createIdleGateway(): SiteAccessGateway = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}

class RotationHarnessActivity : ComponentActivity() {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = requireNotNull(activeHarness).vmFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val harness = requireNotNull(activeHarness)
        setContent {
            composedActivities.add(this)
            NextAppRoot(
                settingsFlow = harness.settingsRepo.settings,
                currentAccountFlow = harness.authRepo.currentAccount,
                smsUnreadCountFlow = harness.smsUnreadFlow,
                notifyUnreadCountFlow = harness.notifyUnreadFlow,
            )
        }
    }

    companion object {
        var activeHarness: NextAppTracerBulletTest.TestHarness? = null
        val composedActivities = mutableSetOf<RotationHarnessActivity>()
    }
}

private class FakeHiltComponentActivity : ComponentActivity(), GeneratedComponentManagerHolder {
    override fun generatedComponent(): Any = Any()
    override fun componentManager(): GeneratedComponentManager<*> = GeneratedComponentManager { Any() }
}
