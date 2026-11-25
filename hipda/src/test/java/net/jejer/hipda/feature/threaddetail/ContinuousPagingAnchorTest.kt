package net.jejer.hipda.feature.threaddetail

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
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
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖任务卡 E2d 全部验收项（100% 离线）：
 * 1. B4-E2a ①（最高优先级）：`mainClock.autoAdvance = false` 下从第 2 页顶部触发向上加载第 1 页并合入数据，
 *    逐帧推进并断言每一帧（含数据合入后的第 1 帧）原首可见楼层距屏幕顶部偏移变化均 ≤ 1dp（覆盖 offset == 0、
 *    offset < 0 以及顶部状态行可见时的 offset > 0 三种位置）；
 * 2. 向下距尾 5 楼自动预取、重复滚动单飞防重入、楼层去重、头尾各自失败原地 `HiListFooter(Failed)` 整行点击重试、
 *    末页展示 `没有更多了`；
 * 3. 底部翻页栏页码随连续加载后的当前可见楼层所在页实时同步；
 * 4. 切换 `只看楼主` 或跨页跳转正确重置连续加载区间并取消在途预取请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ContinuousPagingAnchorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun upwardPrepend_fromTopOfPage2_zeroJumpOnEveryFrameIncludingFirstMergeFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5001", title = "向上插页同帧锚定测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 跳至第 2 页并确认停留在第 2 页 1 号楼（即全帖第 21 楼）
        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        assertEquals(20, vm.uiState.value.floors.size)

        val anchorText = "连续加载第 2 页第 1 楼正文"
        val anchorKey = "floor:5001_p2_1"
        val anchorTopInitial = composeRule.onNodeWithText(anchorText, substring = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot.top

        // 挂起第 1 页响应，先向下滚至第 6 楼再向上滚回第 2 页顶部触发自动预取第 1 页
        val page1CallsBefore = repo.requestedPages.count { it == 1 }
        val page1Gate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = page1Gate
        composeRule.runOnIdle { listState.requestScrollToItem(index = 5, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()

        assertTrue("滚近第 2 页头部应自动触发向上加载第 1 页", vm.uiState.value.isPrepending)
        assertEquals(page1CallsBefore + 1, repo.requestedPages.count { it == 1 })
        val topWhileLoading = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue(
            "向上加载启动帧原首楼不得跳动（diff=${abs(topWhileLoading - anchorTopInitial) / density}dp）",
            abs(topWhileLoading - anchorTopInitial) / density <= 1.0f,
        )

        // 关闭自动推进时钟，放行第 1 页合入并逐帧断言每一帧（必须覆盖合入第 1 帧）偏差 ≤ 1dp
        composeRule.mainClock.autoAdvance = false
        page1Gate.complete(Unit)

        var mergeFrameObserved = false
        for (frame in 1..15) {
            composeRule.mainClock.advanceTimeByFrame()
            val mergedNow = vm.uiState.value.floors.size == 40 && vm.uiState.value.firstLoadedPage == 1
            if (mergedNow) mergeFrameObserved = true
            val currentTop = composeRule.onNodeWithText(anchorText, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
            val diffDp = abs(currentTop - topWhileLoading) / density
            assertTrue(
                "第 $frame 帧（merged=$mergedNow）原首可见楼层距顶偏差 ${diffDp}dp 超过 1dp（before=$topWhileLoading, now=$currentTop）",
                diffDp <= 1.0f,
            )
            if (mergedNow) {
                val visibleAnchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }
                assertNotNull("第 $frame 帧合入后原首可见楼层必须仍在视口内", visibleAnchor)
                assertEquals("合入后第 2 页首楼应精确平移至索引 20", 20, visibleAnchor!!.index)
            }
        }
        assertTrue("逐帧检查必须实际观测到第 1 页数据合入首帧", mergeFrameObserved)

        // 恢复自动时钟并向上滚入第 1 页，确认第 1 页楼层已正确接在顶部
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("连续加载第 1 页第 1 楼正文", substring = true).assertIsDisplayed()
    }

    @Test
    fun upwardPrepend_whenTopFooterVisibleAboveAnchor_positiveOffsetZeroJumpEveryFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5002", title = "顶部状态行露出时同帧锚定测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        repo.failPagesOnce += 1

        // 在第 2 页顶部向下拉触发向上加载，首次失败后顶部原地露出“加载失败，点击重试”，
        // 此时第 2 页第 1 楼位于状态行下方（item.offset > 0）
        scrollContainer().performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("加载失败，点击重试")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        val anchorText = "连续加载第 2 页第 1 楼正文"
        val anchorKey = "floor:5002_p2_1"
        val anchorItemBefore = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }
        assertTrue("点击重试前原首可见楼层应位于顶部失败行下方（offset > 0，实际=${anchorItemBefore.offset}）", anchorItemBefore.offset > 0)
        val anchorTopBefore = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

        // 挂起重试请求，点击整行重试变为 Loading，再在 autoAdvance=false 下放行合入逐帧检查
        val retryGate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = retryGate
        composeRule.onNodeWithText("加载失败，点击重试").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正在加载…").assertIsDisplayed()

        val anchorTopWhileRetrying = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue(
            "点击重试切至加载态时原首楼不得跳动（diff=${abs(anchorTopWhileRetrying - anchorTopBefore) / density}dp）",
            abs(anchorTopWhileRetrying - anchorTopBefore) / density <= 1.0f,
        )

        composeRule.mainClock.autoAdvance = false
        retryGate.complete(Unit)

        var mergeFrameObserved = false
        for (frame in 1..15) {
            composeRule.mainClock.advanceTimeByFrame()
            val mergedNow = vm.uiState.value.floors.size == 40 && vm.uiState.value.firstLoadedPage == 1
            if (mergedNow) mergeFrameObserved = true
            val currentTop = composeRule.onNodeWithText(anchorText, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
            val diffDp = abs(currentTop - anchorTopWhileRetrying) / density
            assertTrue(
                "顶部状态行消失并合入第 1 页第 $frame 帧（merged=$mergedNow）原首楼偏差 ${diffDp}dp 超过 1dp",
                diffDp <= 1.0f,
            )
        }
        assertTrue("必须实际观测到重试合入第 1 页首帧", mergeFrameObserved)
    }

    @Test
    fun upwardPrepend_withPartialScrollOffset_preservesPixelOffsetOnEveryFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5003", title = "非零偏移向上插页锚定测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()

        val page1Gate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = page1Gate

        // 先滚到第 6 楼（不触发尾部预取），再向上滚至第 3 楼带 34px 局部偏移，触发自动向上预取
        composeRule.runOnIdle { listState.requestScrollToItem(index = 5, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 2, scrollOffset = 34) }
        composeRule.waitForIdle()

        assertTrue(vm.uiState.value.isPrepending)
        val expectedMergedCount = vm.uiState.value.floors.size + 20
        val anchorText = "连续加载第 2 页第 3 楼正文"
        val anchorTopBefore = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

        composeRule.mainClock.autoAdvance = false
        page1Gate.complete(Unit)

        var mergeFrameObserved = false
        for (frame in 1..15) {
            composeRule.mainClock.advanceTimeByFrame()
            if (vm.uiState.value.floors.size == expectedMergedCount && vm.uiState.value.firstLoadedPage == 1) {
                mergeFrameObserved = true
            }
            val currentTop = composeRule.onNodeWithText(anchorText, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
            val diffDp = abs(currentTop - anchorTopBefore) / density
            assertTrue("带像素偏移合入第 $frame 帧偏差 ${diffDp}dp 超过 1dp", diffDp <= 1.0f)
        }
        assertTrue(mergeFrameObserved)
    }

    @Test
    fun downwardPrefetchWithin5Floors_singleFlightDedupAndEndFooter() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3, overlapLastFloorOnNextPage = true)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5004", title = "向下预取与防重测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(1), repo.requestedPages.toList())

        // 滚至 index=2 时距页尾仍大于 5 楼，不得提前预取第 2 页
        composeRule.runOnIdle { listState.requestScrollToItem(index = 2, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals("距页尾超过 5 楼时不应触发下一页预取", listOf(1), repo.requestedPages.toList())

        // 挂起第 2 页响应，滚至距页尾 5 楼内触发自动预取，并反复滚动验证单飞防重入
        val page2Gate = CompletableDeferred<Unit>()
        repo.gateForPage[2] = page2Gate
        composeRule.runOnIdle { listState.requestScrollToItem(index = 9, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals("进入距页尾 5 楼内应自动发起第 2 页请求", listOf(1, 2), repo.requestedPages.toList())
        assertTrue(vm.uiState.value.isAppending)

        composeRule.runOnIdle { listState.requestScrollToItem(index = 10, scrollOffset = 20) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 12, scrollOffset = 0) }
        composeRule.waitForIdle()
        scrollContainer().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals("第 2 页在途期间重复滚动不得重复发包", listOf(1, 2), repo.requestedPages.toList())

        // 放行第 2 页：校验重复 postId 已去重（20 + 20 = 40，不含重复的 p1_20）
        page2Gate.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        assertEquals(40, vm.uiState.value.floors.size)
        assertEquals(40, vm.uiState.value.floors.map { it.postId }.distinct().size)

        // 继续滚至第 2 页尾 5 楼内自动预取第 3 页（末页），滚到底部展示“没有更多了”
        composeRule.runOnIdle { listState.requestScrollToItem(index = 31, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals(listOf(1, 2, 3), repo.requestedPages.toList())
        assertEquals(3, vm.uiState.value.lastLoadedPage)
        assertEquals(60, vm.uiState.value.floors.size)

        composeRule.runOnIdle { listState.requestScrollToItem(index = 60, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有更多了").assertIsDisplayed()

        // 到达末页后继续向下滑不得产生多余请求
        scrollContainer().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(listOf(1, 2, 3), repo.requestedPages.toList())
    }

    @Test
    fun headAndTailFailures_preserveLoadedFloorsAndRetryIndependently() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5005", title = "头尾失败独立重试测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        repo.failPagesOnce += setOf(1, 3)

        // 1. 向下加载第 3 页失败：保留已载第 2 页楼层，尾部渲染整行可点的“加载失败，点击重试”
        composeRule.runOnIdle { listState.requestScrollToItem(index = 14, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals("尾部失败不影响已载楼层", 20, vm.uiState.value.floors.size)
        assertNotNull(vm.uiState.value.appendError)

        val callsAfterTailFailure = repo.requestedPages.size
        composeRule.runOnIdle { listState.requestScrollToItem(index = 20, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals("尾部失败态继续滚动不得自动刷包重试", callsAfterTailFailure, repo.requestedPages.size)

        composeRule.onNodeWithText("加载失败，点击重试")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(3, vm.uiState.value.lastLoadedPage)
        assertEquals(40, vm.uiState.value.floors.size)
        composeRule.runOnIdle { listState.requestScrollToItem(index = 40, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有更多了").assertIsDisplayed()

        // 2. 向上滚回第 2 页顶部加载第 1 页失败：保留已载第 2-3 页楼层，头部渲染整行可点的“加载失败，点击重试”
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals("头部失败不影响已载第 2-3 页楼层", 40, vm.uiState.value.floors.size)
        assertNotNull(vm.uiState.value.prependError)
        composeRule.onNodeWithText("加载失败，点击重试")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(3, vm.uiState.value.lastLoadedPage)
        assertEquals(60, vm.uiState.value.floors.size)
    }

    @Test
    fun paginationBarPageIndicator_staysSyncedWithVisibleFloorPageAfterContinuousPaging() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5006", title = "底栏页码与可见楼层同步测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()
        composeRule.onNodeWithText("上一页").assertIsNotEnabled()
        composeRule.onNodeWithText("下一页").assertIsEnabled()

        // 向下滚动预取第 2 页：首可见楼层仍在第 1 页时，底栏保持“第 1 / 3 页”
        composeRule.runOnIdle { listState.requestScrollToItem(index = 10, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()

        // 继续滚入第 2 页楼层（index=22 为第 2 页第 3 楼）：底栏实时切换为“第 2 / 3 页”且“上一页”可用
        composeRule.runOnIdle { listState.requestScrollToItem(index = 22, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 2 / 3 页").assertIsDisplayed()
        assertEquals(2, vm.uiState.value.page)
        composeRule.onNodeWithText("上一页").assertIsEnabled()
        composeRule.onNodeWithText("下一页").assertIsEnabled()

        // 滚至第 2 页末尾预取第 3 页并滚入第 3 页楼层：底栏切换为“第 3 / 3 页”且“下一页”禁用
        composeRule.runOnIdle { listState.requestScrollToItem(index = 31, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 42, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 3 / 3 页").assertIsDisplayed()
        assertEquals(3, vm.uiState.value.page)
        composeRule.onNodeWithText("上一页").assertIsEnabled()
        composeRule.onNodeWithText("下一页").assertIsNotEnabled()

        // 向上滚回第 1 页楼层：底栏切回“第 1 / 3 页”
        composeRule.runOnIdle { listState.requestScrollToItem(index = 1, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()
        assertEquals(1, vm.uiState.value.page)

        // 从第 2 页向上预取第 1 页后：停留在第 2 页楼层时底栏仍显示“第 2 / 3 页”，滚入第 1 页后切为“第 1 / 3 页”
        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 2 / 3 页").assertIsDisplayed()
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        composeRule.onNodeWithText("第 2 / 3 页").assertIsDisplayed()

        composeRule.runOnIdle { listState.requestScrollToItem(index = 2, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()
        assertEquals(1, vm.uiState.value.page)
    }

    @Test
    fun concurrentOppositeDirectionLoads_mergeIndependentlyWithoutInterference() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5008", title = "双向并发预取测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()

        val page1Gate = CompletableDeferred<Unit>()
        val page3Gate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = page1Gate
        repo.gateForPage[3] = page3Gate

        composeRule.runOnIdle {
            vm.loadPreviousPage()
            vm.loadNextPage()
            vm.loadPreviousPage()
            vm.loadNextPage()
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isPrepending)
        assertTrue(vm.uiState.value.isAppending)
        assertEquals(1, repo.requestedPages.drop(2).count { it == 1 })
        assertEquals(1, repo.requestedPages.drop(2).count { it == 3 })

        page3Gate.complete(Unit)
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isAppending)
        assertTrue(vm.uiState.value.isPrepending)
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(3, vm.uiState.value.lastLoadedPage)
        assertEquals(40, vm.uiState.value.floors.size)

        page1Gate.complete(Unit)
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isPrepending)
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(3, vm.uiState.value.lastLoadedPage)
        assertEquals(60, vm.uiState.value.floors.size)
        assertEquals(60, vm.uiState.value.floors.map { it.postId }.distinct().size)
    }

    @Test
    fun nonAdjacentJumpAndAuthorFilter_resetLoadedRangeAndDropStaleInFlightPages() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 5)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "5007", title = "重置区间与防串页测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 挂起第 2 页自动预取，在途期间直接跨页跳至第 4 页，随后放行旧第 2 页不得污染第 4 页区间
        val stalePage2Gate = CompletableDeferred<Unit>()
        repo.gateForPage[2] = stalePage2Gate
        composeRule.runOnIdle { listState.requestScrollToItem(index = 10, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isAppending)

        composeRule.runOnIdle { vm.loadPage(4) }
        composeRule.waitForIdle()
        stalePage2Gate.complete(Unit)
        composeRule.waitForIdle()

        assertEquals(4, vm.uiState.value.firstLoadedPage)
        assertEquals(4, vm.uiState.value.lastLoadedPage)
        assertEquals(20, vm.uiState.value.floors.size)
        assertTrue(vm.uiState.value.floors.all { it.floor.page == 4 })
        assertFalse(vm.uiState.value.isAppending)

        // 滚到第 4 页某楼层后开启“只看楼主”，重置连续区间；退出后恢复第 4 页并定位回原楼
        composeRule.runOnIdle { listState.requestScrollToItem(index = 4, scrollOffset = 20) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("只看楼主").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(1, vm.uiState.value.lastLoadedPage)

        composeRule.onNodeWithContentDescription("退出只看").performClick()
        composeRule.waitForIdle()
        assertEquals(4, vm.uiState.value.firstLoadedPage)
        assertEquals(4, vm.uiState.value.lastLoadedPage)
        assertEquals(4, listState.firstVisibleItemIndex)
        assertEquals(20, listState.firstVisibleItemScrollOffset)
    }

    private fun scrollContainer() = composeRule.onNode(
        SemanticsMatcher("hasVerticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config },
    )

    private class PagingFakeThreadDetailRepository(
        private val totalPages: Int = 3,
        private val overlapLastFloorOnNextPage: Boolean = false,
    ) : ThreadDetailRepository {
        val requestedPages = CopyOnWriteArrayList<Int>()
        val gateForPage = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failPagesOnce = mutableSetOf<Int>()

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            requestedPages += page
            gateForPage.remove(page)?.await()
            if (failPagesOnce.remove(page)) {
                return ThreadDetailError.NetworkError("simulated page $page failure")
            }
            val baseFloors = (1..20).map { idx ->
                val isOp = idx == 1 || idx % 5 == 0
                val extraLines = when (idx % 3) {
                    0 -> "\n补充段落用于制造不等高楼层，验证向上插页锚定在变高列表下仍然零跳动。"
                    1 -> "\n第二段说明文字。"
                    else -> ""
                }
                PostFloor(
                    postId = "${tid}_p${page}_$idx",
                    floor = (page - 1) * 20 + idx,
                    page = page,
                    uid = if (isOp) "100" else "${200 + idx}",
                    author = if (isOp) "楼主大人" else "坛友$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 02:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(
                        ContentBlock.Text(
                            listOf(InlineSpan.Plain("连续加载第 $page 页第 $idx 楼正文$extraLines")),
                        ),
                    ),
                )
            }
            val withOverlap = if (overlapLastFloorOnNextPage && page > 1 && filter is ThreadDetailFilter.All) {
                val duplicateFromPrev = PostFloor(
                    postId = "${tid}_p${page - 1}_20",
                    floor = (page - 1) * 20,
                    page = page - 1,
                    uid = "100",
                    author = "楼主大人",
                    avatarUrl = null,
                    timePost = "2026-11-26 02:20",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("重复楼层应被去重")))),
                )
                listOf(duplicateFromPrev) + baseFloors
            } else {
                baseFloors
            }
            val filtered = when (filter) {
                ThreadDetailFilter.All -> withOverlap
                is ThreadDetailFilter.AuthorOnly -> withOverlap.filter { it.uid == "100" }
                is ThreadDetailFilter.UserOnly -> withOverlap.filter { it.uid == filter.uid }
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "连续加载主题 $tid",
                    page = page,
                    lastPage = if (filter is ThreadDetailFilter.All) totalPages else 1,
                    floors = filtered,
                    postsPerPageEstimate = 20,
                    meta = emptyMeta(),
                    hasPostList = true,
                ),
                filter = if (filter is ThreadDetailFilter.AuthorOnly) ThreadDetailFilter.AuthorOnly("100") else filter,
                threadAuthorId = "100",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, totalPages, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "5001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
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
        private fun emptyMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "100", username = "楼主大人"),
            formhash = "abcd1234",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
