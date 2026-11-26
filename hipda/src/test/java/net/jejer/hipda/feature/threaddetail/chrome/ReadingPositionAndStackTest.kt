package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailFilterUi
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E4 验收项 1 与负责人追加 #1、#3、#4：
 * 1. `R-BATCH5 B4-E4 ①`：首轮恢复门闩在慢加载（`CompletableDeferred` 挂起 `loadPage(5)`）、推进虚拟时钟与中途退出期间，
 *    严格保证 `saveReadingPosition` 调用次数 `== 0`；恢复完成后滚动才触发去抖落盘；
 * 2. 打开帖子自动从 `D5.getReadingPosition(tid)` 恢复 `(page, pid, offsetPx, filterMode)` 并经单一滚动权威定位；
 * 3. `PositionStack` 在跳页 / 跳楼 / 查看新回复前压栈，系统返回逐级回位后再离开帖子；
 * 4. `R-E2c S1-R2` 回归：上下预取在途时触发本页跳楼，必须同步取消在途作业并复位 `isPrepending`/`isAppending`，后续连续加载仍正常触发；
 * 5. 三栏与单栏返回优先级：`PositionStack` 仅在非空时优先于 `E3` 多栏返回栈拦截，栈空后按 `E3` 顺序退出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReadingPositionAndStackTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hydrationGate_slowLoadAndMidLoadExit_zeroSavesBeforeHydrated_thenDebouncesAfterRestore() {
        val page5Gate = CompletableDeferred<Unit>()
        val repo = PositionTestRepository(
            totalPages = 6,
            savedPositions = mutableMapOf(
                "8001" to ReadingPositionEntity(
                    tid = "8001",
                    page = 5,
                    pid = "p5_6",
                    offsetPx = 42,
                    filterMode = ReadingFilterMode.NONE,
                    updatedAt = 1000L,
                ),
            ),
        ).apply {
            pageDelayHook = { page -> if (page == 5) page5Gate.await() }
        }
        val vm1 = ThreadDetailViewModel(repo, createIdleGateway())
        val vm2 = ThreadDetailViewModel(repo, createIdleGateway())
        var activeSession by mutableIntStateOf(1)
        lateinit var listState2: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                when (activeSession) {
                    1 -> ThreadDetailPane(
                        args = ThreadNavArgs(tid = "8001", title = "首轮恢复门闩测试"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm1,
                    )
                    2 -> {
                        listState2 = rememberLazyListState()
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "8001", title = "首轮恢复门闩测试"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            listState = listState2,
                            viewModel = vm2,
                        )
                    }
                    else -> Unit
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 首次恢复第 5 页在途：推进虚拟时钟与主线程 Looper 并尝试触发观测回调、再模拟用户中途退出
        assertTrue("首轮恢复在途应保持 loading", vm1.uiState.value.isLoading)
        composeRule.mainClock.advanceTimeBy(2_000L)
        ShadowLooper.idleMainLooper(2_000L, TimeUnit.MILLISECONDS)
        composeRule.runOnIdle {
            vm1.onReadingPositionChanged(page = 1, postId = "p1_1", scrollOffsetPx = 0)
            vm1.flushReadingPosition()
        }
        activeSession = 0
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)

        assertEquals("B4-E4 ①：首轮恢复完成前严禁写回 Room（防止 (1,0) 冲写历史进度）", 0, repo.saveCalls.size)

        // 2. 重新进入会话并放行第 5 页加载：经 PrependAnchorController 恢复到 p5_6 offset=42，未滚动前仍不重复落盘；
        //    真正滚动后经去抖自动保存新进度
        activeSession = 2
        composeRule.waitForIdle()
        assertEquals("放行前仍必须为 0 次保存", 0, repo.saveCalls.size)

        page5Gate.complete(Unit)
        composeRule.waitForIdle()

        assertEquals(5, vm2.uiState.value.page)
        assertEquals("应经单一滚动权威恢复至 p5_6（索引 5）", 5, listState2.firstVisibleItemIndex)
        assertEquals("应恢复原始像素偏移 42", 42, listState2.firstVisibleItemScrollOffset)

        composeRule.mainClock.advanceTimeBy(1_000L)
        ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
        assertEquals("刚恢复回原锚点未移动时不应冗余冲写", 0, repo.saveCalls.size)

        // 滚动至 p5_9 offset=28，推进时钟与主线程 Looper 越过 400ms 去抖窗口后落盘
        composeRule.runOnIdle { listState2.requestScrollToItem(index = 8, scrollOffset = 28) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600L)
        ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()

        assertEquals("恢复后滚动超过去抖时间应自动保存 1 次", 1, repo.saveCalls.size)
        val saved = repo.saveCalls.single()
        assertEquals("8001", saved.tid)
        assertEquals(5, saved.page)
        assertEquals("p5_9", saved.pid)
        assertEquals(28, saved.offsetPx)
        assertEquals(ReadingFilterMode.NONE, saved.filterMode)
    }

    @Test
    fun savedPosition_restoresFilterModePageAndOffsetViaSingleAuthority() {
        val repo = PositionTestRepository(
            totalPages = 4,
            savedPositions = mutableMapOf(
                "8002" to ReadingPositionEntity(
                    tid = "8002",
                    page = 2,
                    pid = "p2_4",
                    offsetPx = 30,
                    filterMode = ReadingFilterMode.OP,
                    filterUid = "1001",
                    updatedAt = 2000L,
                ),
            ),
        )
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8002", title = "带只看楼主过滤恢复测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(2, vm.uiState.value.page)
        assertTrue(vm.uiState.value.filter is ThreadDetailFilterUi.AuthorOnly)
        assertEquals(3, listState.firstVisibleItemIndex)
        assertEquals(30, listState.firstVisibleItemScrollOffset)
        composeRule.onNodeWithText("只看楼主").assertIsDisplayed()
    }

    @Test
    fun positionStack_pushesBeforePageJumpQuoteFloorAndNewReply_andPopsInLifoOrder() {
        val repo = PositionTestRepository(totalPages = 5)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var hostBackDispatcher: OnBackPressedDispatcher? = null
        var exitedCount = 0
        lateinit var listState: LazyListState

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler(enabled = true) { exitedCount++ }
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8003", title = "位置栈多级返回测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = { exitedCount++ },
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 起点：第 1 页第 6 楼（index=5, offset=24）
        composeRule.runOnIdle { listState.requestScrollToItem(index = 5, scrollOffset = 24) }
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 跳转 1：跳页到第 3 页（压入 1 页 p1_6 @ 24）
        composeRule.runOnIdle { vm.jumpToPage(3) }
        composeRule.waitForIdle()
        assertEquals(3, vm.uiState.value.page)
        assertTrue(vm.uiState.value.canPopPositionStack)

        // 滚到第 3 页第 7 楼（index=6, offset=16），执行跳转 2：本页跳楼至 p3_1
        composeRule.runOnIdle { listState.requestScrollToItem(index = 6, scrollOffset = 16) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vm.jumpToFloor("p3_1") }
        composeRule.waitForIdle()
        assertEquals(0, listState.firstVisibleItemIndex)

        // 跳转 3：查看新回复（末页第 5 页）
        composeRule.runOnIdle { vm.jumpToNewReply() }
        composeRule.waitForIdle()
        assertEquals(5, vm.uiState.value.page)

        // 返回 1：回第 3 页 p3_1
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, exitedCount)
        assertEquals(3, vm.uiState.value.page)
        assertEquals(0, listState.firstVisibleItemIndex)

        // 返回 2：回第 3 页 p3_7 @ 16（同页零网络原位恢复）
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, exitedCount)
        assertEquals(3, vm.uiState.value.page)
        assertEquals(6, listState.firstVisibleItemIndex)
        assertEquals(16, listState.firstVisibleItemScrollOffset)

        // 返回 3：回第 1 页 p1_6 @ 24
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, exitedCount)
        assertEquals(1, vm.uiState.value.page)
        assertEquals(5, listState.firstVisibleItemIndex)
        assertEquals(24, listState.firstVisibleItemScrollOffset)
        assertFalse("位置栈此时应已清空", vm.uiState.value.canPopPositionStack)

        // 返回 4：栈空，交还外层退出帖子
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("栈空后再按返回必须交还外层离开帖子", 1, exitedCount)
    }

    /**
     * 负责人追加 #1（必修 `R-E2c S1-R2`）：上下预取均在途时触发本页跳楼，必须同步取消在途作业并复位
     * `isPrepending`/`isAppending`，后续上下连续加载仍可正常触发并合入（回退此修复本用例必红）。
     */
    @Test
    fun samePageJumpToFloor_duringInFlightPrependAndAppend_cancelsJobsAndAllowsSubsequentPaging() {
        val prependGate = CompletableDeferred<Unit>()
        val appendGate = CompletableDeferred<Unit>()
        var blockDirectional = true
        val repo = PositionTestRepository(totalPages = 3).apply {
            pageDelayHook = { page ->
                if (blockDirectional) {
                    if (page == 1) prependGate.await()
                    if (page == 3) appendGate.await()
                }
            }
        }
        val vm = ThreadDetailViewModel(repo, createIdleGateway())

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8004", title = "R-E2c S1-R2 回归测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 先放行加载第 2 页作为起点
        blockDirectional = false
        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)

        // 同时发起向上（第 1 页）和向下（第 3 页）预取并挂起在途
        blockDirectional = true
        composeRule.runOnIdle {
            vm.loadPreviousPage()
            vm.loadNextPage()
        }
        composeRule.waitForIdle()
        assertTrue("前提：向上预取应在途", vm.uiState.value.isPrepending)
        assertTrue("前提：向下预取应在途", vm.uiState.value.isAppending)

        // 在途期间触发本页跳楼（递增 sessionGeneration）
        composeRule.runOnIdle { vm.jumpToFloor("p2_2") }
        composeRule.waitForIdle()

        assertFalse("R-E2c S1-R2：本页跳楼必须复位 isPrepending", vm.uiState.value.isPrepending)
        assertFalse("R-E2c S1-R2：本页跳楼必须复位 isAppending", vm.uiState.value.isAppending)

        // 放行旧挂起门闩并解除新请求阻塞，再次触发向上与向下连续加载必须全部成功合入
        blockDirectional = false
        prependGate.complete(Unit)
        appendGate.complete(Unit)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            vm.loadPreviousPage()
            vm.loadNextPage()
        }
        composeRule.waitForIdle()

        assertEquals("本页跳楼后向上连续加载仍必须正常合入第 1 页", 1, vm.uiState.value.firstLoadedPage)
        assertEquals("本页跳楼后向下连续加载仍必须正常合入第 3 页", 3, vm.uiState.value.lastLoadedPage)
        assertEquals(60, vm.uiState.value.floors.size)
    }

    /** 负责人追加 #3（三栏返回优先级）：跳页/跳楼 → 返回先回原位置 → 再返回按 E3 既有顺序关闭右栏。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun backPriority_threePane_popsPositionStackBeforeClosingRightPane() {
        val harness = MultiPaneTestHarness()
        var hostBackDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            harness.RenderRoot()
        }
        composeRule.waitForIdle()

        // 1. 点击打开帖子 1001 进入右栏
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        assertEquals("1001", harness.hostViewModel.state.value.selectedThread?.tid)

        // 2. 通过中段进度胶囊展开跳页条并跳到末页（压入 PositionStack）
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 3 页，共 3 页，点按展开跳页").assertIsDisplayed()

        // 3. 第一次系统返回：仅从 PositionStack 回到第 1 页，右栏帖子仍保持打开
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals("PositionStack 非空时返回不得关闭右栏", "1001", harness.hostViewModel.state.value.selectedThread?.tid)
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").assertIsDisplayed()

        // 4. 第二次系统返回：PositionStack 已空，按 E3 既有优先级清空右栏回到空占位
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertNull("PositionStack 栈空后应按 E3 顺序关闭右栏", harness.hostViewModel.state.value.selectedThread)
    }

    private class PositionTestRepository(
        private val totalPages: Int,
        private val savedPositions: MutableMap<String, ReadingPositionEntity> = mutableMapOf(),
    ) : ThreadDetailRepository {
        val saveCalls = CopyOnWriteArrayList<ReadingPositionEntity>()
        var pageDelayHook: (suspend (Int) -> Unit)? = null

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            pageDelayHook?.invoke(page)
            val resolvedPage = page.coerceIn(1, totalPages)
            val floors = (1..20).map { idx ->
                val floorNum = (resolvedPage - 1) * 20 + idx
                PostFloor(
                    postId = "p${resolvedPage}_$idx",
                    floor = floorNum,
                    page = resolvedPage,
                    uid = if (idx == 1 || filter is ThreadDetailFilter.AuthorOnly) "1001" else "${1000 + idx}",
                    author = if (idx == 1) "楼主" else "坛友$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 10:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain(
                                    "第 $resolvedPage 页第 $idx 楼正文内容：用于确保整页高度足以自由滚动到任意中间楼层与精确像素偏移量，" +
                                        "验证首轮恢复门闩、阅读位置记忆与位置栈逐级出栈恢复。",
                                ),
                            ),
                        ),
                    ),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "阅读位置测试帖",
                    page = resolvedPage,
                    lastPage = totalPages,
                    floors = floors,
                    postsPerPageEstimate = 20,
                    meta = DiscuzPageMeta(
                        session = DiscuzSessionInfo(true, "1001", "楼主"),
                        formhash = "fh123456",
                        errorMessage = null,
                        promptCounts = PromptCounts(),
                    ),
                    hasPostList = true,
                ),
                filter = filter,
                threadAuthorId = "1001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, totalPages, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val page = Regex("p(\\d+)_").find(pid)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val res = loadPage(tid ?: "8001", page, ThreadDetailFilter.All, recordHistory, forumName, priority)
            return if (res is ThreadDetailLoadResult.Success) res.copy(targetPostId = pid) else res
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = savedPositions[tid]

        override suspend fun saveReadingPosition(
            tid: String,
            page: Int,
            pid: String,
            offsetPx: Int,
            filterMode: ReadingFilterMode,
            filterUid: String?,
            filterUserName: String?,
        ) {
            val entity = ReadingPositionEntity(tid, page, pid, offsetPx, filterMode, filterUid, filterUserName, System.currentTimeMillis())
            savedPositions[tid] = entity
            saveCalls += entity
        }

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
        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
