package net.jejer.hipda.feature.threaddetail

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
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
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.state.HiFooterState
import net.jejer.hipda.ui.designsystem.state.HiListFooter
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * 覆盖任务卡 E2d 全部验收项与 R-E2d B1/S1 修复项（100% 离线）：
 * 1. B4-E2a ① + R-E2d B1：`mainClock.autoAdvance = false` 下通过 `PrependFrameProbe`（挂载
 *    `Modifier.onLayoutRectChanged(0, 0)` 于 `AndroidComposeView.measureAndLayout()` 末尾
 *    `RectManager.dispatchCallbacks()`、严格早于 `LaunchedEffect` 唤醒与下一帧 `requestLayout`）
 *    并结合 `composeRule.runWithoutImplicitWait`，真捕获合入首帧并逐帧断言偏差 ≤ 1dp（覆盖 offset == 0、
 *    offset > 0 与 offset < 0）；
 * 2. B1 双反例自证：同一套逐帧校验器对 `LaunchedEffect` 滞后回滚反例与 `onGloballyPositioned` 内改调
 *    `requestScrollToItem` 反例均在合入首帧必定 FAIL，正式 `ThreadDetailPane` 必定 PASS；
 * 3. 向下距尾 5 楼自动预取、重复滚动单飞防重入、楼层去重、头尾各自失败原地 `HiListFooter(Failed)` 整行点击重试、
 *    末页展示 `没有更多了`；
 * 4. 底部翻页栏页码随连续加载后的当前可见楼层所在页实时同步；
 * 5. 切换 `只看楼主`、跨页跳转与 S1 同一 VM/LazyListState 切换新 `tid` 复位至顶部。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ContinuousPagingAnchorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUpImageCacheAndNetwork() {
        ImageAspectRatioCache.Default.clear()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val shadowCm = shadowOf(cm)
        shadowCm.setDefaultNetworkActive(true)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        shadowCm.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
    }

    @After
    fun tearDownImageCache() {
        ImageAspectRatioCache.Default.clear()
    }

    private data class TraversalFrameSample(
        val step: Int,
        val floorsCount: Int,
        val layoutTotalItemsCount: Int,
        val firstLoadedPage: Int,
        val anchorOffsetPx: Int?,
        val anchorIndex: Int?,
        val firstVisibleKey: Any?,
    )

    @Test
    fun upwardPrepend_fromTopOfPage2_zeroJumpOnEveryFrameIncludingFirstMergeFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f
        val anchorKey = "floor:5001_p2_1"
        val anchorText = "连续加载第 2 页第 1 楼正文"

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "5001", title = "向上插页同帧锚定测试"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                    PrependFrameProbe(vm, listState, anchorKey, probeStep, samples)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        assertEquals(20, vm.uiState.value.floors.size)

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

        val anchorOffsetWhileLoading = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.offset
        val topWhileLoading = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue(
            "向上加载启动帧原首楼不得跳动（diff=${abs(topWhileLoading - anchorTopInitial) / density}dp）",
            abs(topWhileLoading - anchorTopInitial) / density <= 1.0f,
        )

        verifyPrependMergeFrameByFrame(
            vm = vm,
            listState = listState,
            anchorKey = anchorKey,
            anchorText = anchorText,
            baselineOffsetPx = anchorOffsetWhileLoading,
            baselineBoundsTopPx = topWhileLoading,
            expectedMergedCount = 40,
            expectedNewAnchorIndex = 20,
            density = density,
            probeStep = probeStep,
            samples = samples,
            releaseMerge = { page1Gate.complete(Unit) },
        )

        // 恢复自动时钟并向上滚入第 1 页，确认第 1 页楼层已正确接在顶部
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("连续加载第 1 页第 1 楼正文", substring = true).assertIsDisplayed()
    }

    @Test
    fun upwardPrepend_whenTopFooterVisibleAboveAnchor_positiveOffsetZeroJumpEveryFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f
        val anchorKey = "floor:5002_p2_1"
        val anchorText = "连续加载第 2 页第 1 楼正文"

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "5002", title = "顶部状态行露出时同帧锚定测试"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                    PrependFrameProbe(vm, listState, anchorKey, probeStep, samples)
                }
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

        val anchorOffsetWhileRetrying = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.offset
        val anchorTopWhileRetrying = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue(
            "点击重试切至加载态时原首楼不得跳动（diff=${abs(anchorTopWhileRetrying - anchorTopBefore) / density}dp）",
            abs(anchorTopWhileRetrying - anchorTopBefore) / density <= 1.0f,
        )

        verifyPrependMergeFrameByFrame(
            vm = vm,
            listState = listState,
            anchorKey = anchorKey,
            anchorText = anchorText,
            baselineOffsetPx = anchorOffsetWhileRetrying,
            baselineBoundsTopPx = anchorTopWhileRetrying,
            expectedMergedCount = 40,
            expectedNewAnchorIndex = 20,
            density = density,
            probeStep = probeStep,
            samples = samples,
            releaseMerge = { retryGate.complete(Unit) },
        )
    }

    @Test
    fun upwardPrepend_withPartialScrollOffset_preservesPixelOffsetOnEveryFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f
        val anchorKey = "floor:5003_p2_3"
        val anchorText = "连续加载第 2 页第 3 楼正文"

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "5003", title = "非零偏移向上插页锚定测试"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                    PrependFrameProbe(vm, listState, anchorKey, probeStep, samples)
                }
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
        val baselineOffsetPx = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.offset
        val anchorTopBefore = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

        verifyPrependMergeFrameByFrame(
            vm = vm,
            listState = listState,
            anchorKey = anchorKey,
            anchorText = anchorText,
            baselineOffsetPx = baselineOffsetPx,
            baselineBoundsTopPx = anchorTopBefore,
            expectedMergedCount = expectedMergedCount,
            expectedNewAnchorIndex = 22,
            density = density,
            probeStep = probeStep,
            samples = samples,
            releaseMerge = { page1Gate.complete(Unit) },
        )
    }

    /**
     * R-E2d B1 反例自证 1：以滞后一帧的 `LaunchedEffect(uiState.prependGeneration)` 调用 `scrollToItem` 补偿，
     * 同一套 `PrependFrameProbe` + `verifyPrependMergeFrameByFrame` 必须在合入首帧必定 FAIL（证明测试能拦截
     * 滞后补偿，不会被 `advanceTimeByFrame()` 末尾的 `scheduler.runCurrent()` 掩盖）。
     */
    @Test
    fun counterexample_launchedEffectCompensation_mustFailOnFirstMergeFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f
        val anchorKey = "floor:5101_p2_1"
        val anchorText = "连续加载第 2 页第 1 楼正文"

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    CounterexampleLaunchedEffectPane(
                        tid = "5101",
                        vm = vm,
                        listState = listState,
                    )
                    PrependFrameProbe(vm, listState, anchorKey, probeStep, samples)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        val page1Gate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = page1Gate
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()
        // 滚至顶部露出 Loading 行，使首项为顶部状态行、锚点楼层位于 offset = +48dp
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()

        val baselineOffsetPx = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.offset
        val baselineTopPx = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue("反例 1 准备态应露出顶部状态行（offset > 0，实际=$baselineOffsetPx）", baselineOffsetPx > 0)

        var caughtFailure: AssertionError? = null
        try {
            verifyPrependMergeFrameByFrame(
                vm = vm,
                listState = listState,
                anchorKey = anchorKey,
                anchorText = anchorText,
                baselineOffsetPx = baselineOffsetPx,
                baselineBoundsTopPx = baselineTopPx,
                expectedMergedCount = 40,
                expectedNewAnchorIndex = 20,
                density = density,
                probeStep = probeStep,
                samples = samples,
                releaseMerge = { page1Gate.complete(Unit) },
            )
        } catch (e: AssertionError) {
            caughtFailure = e
        }
        val err = caughtFailure
            ?: throw AssertionError("反例 1（LaunchedEffect 滞后补偿）在合入首帧必须 FAIL，不应假绿通过")
        val firstMergeSample = samples.firstOrNull {
            it.floorsCount == 40 && it.layoutTotalItemsCount >= 40 && it.firstLoadedPage == 1
        }
        assertNotNull("探针必须截获反例 1 的合入首帧样本（samples=$samples）", firstMergeSample)
        assertEquals("反例 1 必须在合入首帧（第 1 帧）即被拦截（failure=${err.message}）", 1, firstMergeSample!!.step)
        assertEquals(
            "反例 1 合入首帧原首楼应被 20 楼新内容顶出视口（anchorOffsetPx == null），实际样本=$firstMergeSample，失败信息=${err.message}",
            null,
            firstMergeSample.anchorOffsetPx,
        )
        // 验证：即便 LaunchedEffect 在同次 advanceTimeByFrame() 末尾的 scheduler.runCurrent() 中把锚点拉回原位（96px），
        // PrependFrameProbe 依然在合入首帧 traversal 瞬间将其精准拦截（杜绝旧测试假绿）
        val postRunCurrentOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }?.offset
        assertEquals("反例 1 滞后补偿仅在 runCurrent 之后才把锚点拉回基线偏移", baselineOffsetPx, postRunCurrentOffset)
    }

    /**
     * R-E2d B1 反例自证 2：保留 `SideEffect` 落位，但在 `onGloballyPositioned` 中把 `dispatchRawDelta`
     * 劣化为无同步重测的 `requestScrollToItem`（排入下一帧），同一套校验器必须在合入首帧以约 48dp 偏移 FAIL
     * （证明 `PrependFrameProbe` + `runWithoutImplicitWait` 未隐式冲刷下一帧布局）。
     */
    @Test
    fun counterexample_onGloballyPositionedRequestScrollToItem_mustFailOnFirstMergeFrame() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f
        val anchorKey = "floor:5102_p2_1"
        val anchorText = "连续加载第 2 页第 1 楼正文"

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    CounterexampleDeferredRequestScrollPane(
                        tid = "5102",
                        vm = vm,
                        listState = listState,
                    )
                    PrependFrameProbe(vm, listState, anchorKey, probeStep, samples)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        val page1Gate = CompletableDeferred<Unit>()
        repo.gateForPage[1] = page1Gate
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()

        val baselineOffsetPx = listState.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.offset
        val baselineTopPx = composeRule.onNodeWithText(anchorText, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue("反例 2 准备态应露出顶部状态行（offset > 0，实际=$baselineOffsetPx）", baselineOffsetPx > 0)

        var caughtFailure: AssertionError? = null
        try {
            verifyPrependMergeFrameByFrame(
                vm = vm,
                listState = listState,
                anchorKey = anchorKey,
                anchorText = anchorText,
                baselineOffsetPx = baselineOffsetPx,
                baselineBoundsTopPx = baselineTopPx,
                expectedMergedCount = 40,
                expectedNewAnchorIndex = 20,
                density = density,
                probeStep = probeStep,
                samples = samples,
                releaseMerge = { page1Gate.complete(Unit) },
            )
        } catch (e: AssertionError) {
            caughtFailure = e
        }
        val err = caughtFailure
            ?: throw AssertionError("反例 2（onGloballyPositioned 改调 requestScrollToItem）在合入首帧必须 FAIL，不应假绿通过")
        val firstMergeSample = samples.firstOrNull {
            it.floorsCount == 40 && it.layoutTotalItemsCount >= 40 && it.firstLoadedPage == 1
        }
        assertNotNull("探针必须截获反例 2 的合入首帧样本（samples=$samples）", firstMergeSample)
        assertEquals("反例 2 必须在合入首帧（第 1 帧）即被拦截（failure=${err.message}）", 1, firstMergeSample!!.step)
        assertEquals(
            "反例 2 的 SideEffect 已将锚点落位至 0px，但 onGloballyPositioned 中 requestScrollToItem 无同步重测导致合入首帧停在 0px（sample=$firstMergeSample）",
            0,
            firstMergeSample.anchorOffsetPx,
        )
        val firstFrameDriftPx = abs(firstMergeSample.anchorOffsetPx!! - baselineOffsetPx)
        val firstFrameDriftDp = firstFrameDriftPx / density
        assertEquals("反例 2 合入首帧偏差应等于原正偏移（96px / 48.0dp，failure=${err.message}）", baselineOffsetPx, firstFrameDriftPx)
        assertTrue("反例 2 合入首帧偏移应显著大于 1dp（实际=${firstFrameDriftDp}dp）", firstFrameDriftDp >= 40.0f)
        val postFrameNoImplicitWaitTop = composeRule.runWithoutImplicitWait {
            composeRule.onNodeWithText(anchorText, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        }
        val boundsDriftPx = abs(postFrameNoImplicitWaitTop - baselineTopPx)
        assertEquals(
            "反例 2 合入首帧 runWithoutImplicitWait 读取 boundsInRoot 同样应反映完整 48.0dp 跳变（未隐式冲刷下一帧 onLayout）",
            baselineOffsetPx.toFloat(),
            boundsDriftPx,
            0.5f,
        )
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

        composeRule.onNodeWithContentDescription("第 1 楼，点按展开跳页").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").assertIsNotEnabled()
        composeRule.onNodeWithText("末页").assertIsEnabled()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // 向下滚动预取第 2 页：首可见楼层仍在第 1 页（11 楼）时，中段楼层胶囊同步显示 11 楼且仍在第 1 页
        composeRule.runOnIdle { listState.requestScrollToItem(index = 10, scrollOffset = 0) }
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        assertEquals(1, vm.uiState.value.page)
        composeRule.onNodeWithContentDescription("第 11 楼，点按展开跳页").assertIsDisplayed()

        // 继续滚入第 2 页楼层（index=22 为第 2 页第 3 楼即 23 楼）：中段胶囊切换为 23 楼且“首页”“末页”均可用
        composeRule.runOnIdle { listState.requestScrollToItem(index = 22, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 23 楼，点按展开跳页").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(2, vm.uiState.value.page)
        composeRule.onNodeWithText("首页").assertIsEnabled()
        composeRule.onNodeWithText("末页").assertIsEnabled()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // 滚至第 2 页末尾预取第 3 页并滚入第 3 页楼层（43 楼）：中段胶囊切换为 43 楼且“末页”禁用
        composeRule.runOnIdle { listState.requestScrollToItem(index = 31, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 42, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 43 楼，点按展开跳页").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(3, vm.uiState.value.page)
        composeRule.onNodeWithText("首页").assertIsEnabled()
        composeRule.onNodeWithText("末页").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // 向上滚回第 1 页楼层（2 楼）：中段胶囊切回 2 楼且 page 切回第 1 页
        composeRule.runOnIdle { listState.requestScrollToItem(index = 1, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 2 楼，点按展开跳页").assertIsDisplayed()
        assertEquals(1, vm.uiState.value.page)

        // 从第 2 页向上预取第 1 页后：停留在第 2 页楼层（21 楼）时中段胶囊仍显示 21 楼，滚入第 1 页（3 楼）后切为 3 楼
        composeRule.runOnIdle { vm.loadPage(2) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 21 楼，点按展开跳页").assertIsDisplayed()
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.page)
        composeRule.onNodeWithContentDescription("第 21 楼，点按展开跳页").assertIsDisplayed()

        composeRule.runOnIdle { listState.requestScrollToItem(index = 2, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 3 楼，点按展开跳页").assertIsDisplayed()
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

    /**
     * R-E2d S1：同一 `ThreadDetailViewModel` 与 `LazyListState` 切换绑定至新 `tid` 时（`boundTid != null`），
     * 必须自动复位滚动位置至新帖顶部（首次绑定仍保持 `scrollToTop = false`，不干扰预设偏移场景）。
     */
    @Test
    fun bindArgs_rebindingToNewTid_resetsScrollPositionToTop() {
        val repo = PagingFakeThreadDetailRepository(totalPages = 3)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var activeArgs by mutableStateOf(ThreadNavArgs(tid = "6001", title = "旧帖 6001"))
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 0, initialFirstVisibleItemScrollOffset = 48)
                ThreadDetailPane(
                    args = activeArgs,
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        // 首次绑定不强行归零预设偏移（保护既有截图测试）
        assertEquals(48, listState.firstVisibleItemScrollOffset)

        // 将旧帖滚动至深处后再切到新 tid
        composeRule.runOnIdle { listState.requestScrollToItem(index = 6, scrollOffset = 40) }
        composeRule.waitForIdle()
        assertEquals(6, listState.firstVisibleItemIndex)
        assertEquals(40, listState.firstVisibleItemScrollOffset)

        activeArgs = ThreadNavArgs(tid = "6002", title = "新帖 6002")
        composeRule.waitForIdle()

        assertEquals("6002", vm.uiState.value.tid)
        assertEquals("切换新 tid 后首项索引必须复位为 0", 0, listState.firstVisibleItemIndex)
        assertEquals("切换新 tid 后像素偏移必须复位为 0", 0, listState.firstVisibleItemScrollOffset)
        composeRule.onNodeWithText("连续加载第 1 页第 1 楼正文", substring = true).assertIsDisplayed()
    }

    /**
     * V-E2b 合并探针 1A（同帧先插页后图）：首可见楼层 `p201` 含一张越过视口顶部的 `4:3` 图片，
     * 同帧先释放第 1 页向上插页、再释放该图变为 `1:2` 长图；通过 `PrependFrameProbe` + `runWithoutImplicitWait`
     * 逐帧断言 `p201` 尾段与 `p202` 偏移全程 `≤ 1dp`（不丢补偿、不重复补偿）。
     */
    @Test
    fun e2bE2dMerge_sameFrame_prependThenAboveViewportImage_zeroJumpOnEveryFrame() {
        runE2bE2dFirstVisibleFloorImageMergeProbe(
            prependFirst = true,
            delayImageByOneFrame = false,
            label = "SAME_FRAME_PREPEND_THEN_IMAGE",
        )
    }

    /**
     * V-E2b 合并探针 1B（同帧先图后插页）：同帧先释放 `p201` 越顶图片变长、再释放第 1 页向上插页；
     * 验证 `PrependAnchorController` 合并同帧已登记图片高度增量，绝不被旧 `layoutInfo.offset` 覆盖归零。
     */
    @Test
    fun e2bE2dMerge_sameFrame_aboveViewportImageThenPrepend_zeroJumpOnEveryFrame() {
        runE2bE2dFirstVisibleFloorImageMergeProbe(
            prependFirst = false,
            delayImageByOneFrame = false,
            label = "SAME_FRAME_IMAGE_THEN_PREPEND",
        )
    }

    /**
     * V-E2b 合并探针 1C（隔帧）：第 1 帧先合入第 1 页并更新 `layoutInfo`，第 2 帧再释放 `p201` 越顶图变长；
     * 验证布局纪元切换后既不重复补偿也不漏补偿，逐帧偏移全程 `≤ 1dp`。
     */
    @Test
    fun e2bE2dMerge_nextFrame_aboveViewportImageOneFrameAfterPrepend_zeroJumpOnEveryFrame() {
        runE2bE2dFirstVisibleFloorImageMergeProbe(
            prependFirst = true,
            delayImageByOneFrame = true,
            label = "NEXT_FRAME_IMAGE_AFTER_PREPEND",
        )
    }

    /**
     * V-E2b 合并探针 2（跨楼层）：`p202` 为当前首可见楼层（`offset = -10dp`），其上方 `p201` 位于透明顶栏后的
     * `beforeContentPadding` 区域；第 1 页向上插页与 `p201` 图片变长（`4:3 -> 1:2`）同帧完成，
     * 通过 `PrependFrameProbe` + `runWithoutImplicitWait` 逐帧断言 `p202` 偏移全程 `≤ 1dp`（无过度补偿）。
     */
    @Test
    fun e2bE2dMerge_crossFloor_aboveFloorInTopPaddingGrowsSameFrameAsPrepend_zeroJumpOnEveryFrame() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val page1Gate = CompletableDeferred<ThreadDetailLoadResult>()
        val imageGate = CompletableDeferred<Pair<Int, Int>>()
        val tallImageUrl = "https://img.example.invalid/e2b_e2d_cross_floor_tall.jpg"

        val imageLoader = ControllableOfflineImageLoader(ctx).apply { setDeferred(tallImageUrl, imageGate) }
        val repo = ImageMergeThreadRepository(tallImageUrl = tallImageUrl).apply {
            pageInterceptor = { page -> if (page == 1 && firstPageGateArmed) page1Gate.await() else null }
        }
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                        listState = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = "99002", title = "E2b+E2d 跨楼层合并探针"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                listState = listState,
                                viewModel = vm,
                            )
                            PrependFrameProbe(vm, listState, "floor:p202", probeStep, samples)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.nextPage() }
        composeRule.waitForIdle()

        repo.firstPageGateArmed = true
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()

        val offsetPx = (10f * density).toInt()
        composeRule.runOnIdle {
            val p202Idx = if (vm.uiState.value.showPrependHeader) 2 else 1
            runBlocking { listState.scrollToItem(index = p202Idx, scrollOffset = offsetPx) }
        }
        composeRule.waitForIdle()
        assertTrue("前提：p201 应在 beforeContentPadding 区内保持组合", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:p201" })

        val expectedMergedCount = vm.uiState.value.floors.size + 15
        val baselineP202OffsetPx = listState.layoutInfo.visibleItemsInfo.first { it.key == "floor:p202" }.offset
        val baselineP202TopPx = composeRule.onAllNodesWithText("17楼", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot.top

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            page1Gate.complete(repo.buildPageSuccess(page = 1, tid = "99002"))
            imageGate.complete(400 to 800)
            var mergeFrameObserved = false
            for (frame in 1..20) {
                val sampleStart = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val frameSamples = samples.subList(sampleStart, samples.size).toList()
                assertTrue("跨楼层第 $frame 帧必须捕获 traversal 探针样本", frameSamples.isNotEmpty())
                for (sample in frameSamples) {
                    val merged = sample.floorsCount == expectedMergedCount &&
                        sample.layoutTotalItemsCount >= expectedMergedCount &&
                        sample.firstLoadedPage == 1
                    if (merged) mergeFrameObserved = true
                    val off = sample.anchorOffsetPx
                    assertNotNull("跨楼层第 $frame 帧 p202 不得跳出视口（sample=$sample）", off)
                    val driftDp = abs(off!! - baselineP202OffsetPx) / density
                    assertTrue(
                        "跨楼层第 $frame 帧（traversal merged=$merged）p202 偏移 ${driftDp}dp 超过 1dp（before=$baselineP202OffsetPx, now=$off）",
                        driftDp <= 1.0f,
                    )
                    if (merged) assertEquals("跨楼层合入后 p202 应位于索引 16", 16, sample.anchorIndex)
                }

                val currentTop = composeRule.runWithoutImplicitWait {
                    composeRule.onAllNodesWithText("17楼", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .first()
                        .boundsInRoot.top
                }
                val boundsDriftDp = abs(currentTop - baselineP202TopPx) / density
                assertTrue("跨楼层第 $frame 帧（runWithoutImplicitWait）p202 偏差 ${boundsDriftDp}dp 超过 1dp", boundsDriftDp <= 1.0f)
            }
            assertTrue("跨楼层探针必须实际观测到第 1 页合入首帧", mergeFrameObserved)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(expectedMergedCount, vm.uiState.value.floors.size)
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals(16, listState.firstVisibleItemIndex)
        assertEquals("跨楼层变高严禁过度补偿 p202 滚动偏移", offsetPx, listState.firstVisibleItemScrollOffset)
        assertEquals(0.5f, ImageAspectRatioCache.Default.resolveInitialAspectRatio(tallImageUrl), 0.001f)
    }

    private fun runE2bE2dFirstVisibleFloorImageMergeProbe(
        prependFirst: Boolean,
        delayImageByOneFrame: Boolean,
        label: String,
    ) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val page1Gate = CompletableDeferred<ThreadDetailLoadResult>()
        val imageGate = CompletableDeferred<Pair<Int, Int>>()
        val tallImageUrl = "https://img.example.invalid/${label}_tall.jpg"

        val imageLoader = ControllableOfflineImageLoader(ctx).apply { setDeferred(tallImageUrl, imageGate) }
        val repo = ImageMergeThreadRepository(tallImageUrl = tallImageUrl).apply {
            pageInterceptor = { page -> if (page == 1 && firstPageGateArmed) page1Gate.await() else null }
        }
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<TraversalFrameSample>()
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                        listState = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = "99001", title = label),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                listState = listState,
                                viewModel = vm,
                            )
                            PrependFrameProbe(vm, listState, "floor:p202", probeStep, samples)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.nextPage() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 16 楼，点按展开跳页").assertIsDisplayed()

        // 滚过 p201 顶部 4:3 图片，使图片底缘越过视口顶边且 p201 尾段与 p202 可见
        val scrollPx = (410f * density).toInt()
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(index = 0, scrollOffset = scrollPx) } }
        composeRule.waitForIdle()
        assertTrue("前提：p201 越顶图片应已发起加载", imageLoader.requestedUrls.contains(tallImageUrl))

        repo.firstPageGateArmed = true
        composeRule.runOnIdle { vm.loadPreviousPage() }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.showPrependHeader)

        val expectedMergedCount = vm.uiState.value.floors.size + 15
        val scrollOffsetBefore = listState.firstVisibleItemScrollOffset
        val baselineP202OffsetPx = listState.layoutInfo.visibleItemsInfo.first { it.key == "floor:p202" }.offset
        val baselineP202TopPx = composeRule.onAllNodesWithText("17楼", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot.top
        val baselineP201TailTopPx = composeRule.onAllNodesWithText("P201_TAIL_ANCHOR", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot.top

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            if (delayImageByOneFrame) {
                page1Gate.complete(repo.buildPageSuccess(page = 1))
            } else if (prependFirst) {
                page1Gate.complete(repo.buildPageSuccess(page = 1))
                imageGate.complete(400 to 800)
            } else {
                imageGate.complete(400 to 800)
                page1Gate.complete(repo.buildPageSuccess(page = 1))
            }

            var mergeFrameObserved = false
            for (frame in 1..20) {
                if (delayImageByOneFrame && frame == 2) {
                    imageGate.complete(400 to 800)
                }
                val sampleStart = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val frameSamples = samples.subList(sampleStart, samples.size).toList()
                assertTrue("[$label] 第 $frame 帧必须捕获 traversal 探针样本", frameSamples.isNotEmpty())
                for (sample in frameSamples) {
                    val merged = sample.floorsCount == expectedMergedCount &&
                        sample.layoutTotalItemsCount >= expectedMergedCount &&
                        sample.firstLoadedPage == 1
                    if (merged) mergeFrameObserved = true
                    val off = sample.anchorOffsetPx
                    assertNotNull("[$label] 第 $frame 帧 p202 不得跳出视口（sample=$sample）", off)
                    val driftDp = abs(off!! - baselineP202OffsetPx) / density
                    assertTrue(
                        "[$label] 第 $frame 帧（traversal merged=$merged）p202 偏移 ${driftDp}dp 超过 1dp（before=$baselineP202OffsetPx, now=$off）",
                        driftDp <= 1.0f,
                    )
                    if (merged) assertEquals("[$label] 合入后 p202 应位于索引 16", 16, sample.anchorIndex)
                }

                val (p202TopNow, p201TailTopNow) = composeRule.runWithoutImplicitWait {
                    val p202Top = composeRule.onAllNodesWithText("17楼", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .first()
                        .boundsInRoot.top
                    val p201TailTop = composeRule.onAllNodesWithText("P201_TAIL_ANCHOR", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .first()
                        .boundsInRoot.top
                    p202Top to p201TailTop
                }
                val p202DriftDp = abs(p202TopNow - baselineP202TopPx) / density
                val p201TailDriftDp = abs(p201TailTopNow - baselineP201TailTopPx) / density
                assertTrue("[$label] 第 $frame 帧（runWithoutImplicitWait）p202 偏差 ${p202DriftDp}dp 超过 1dp", p202DriftDp <= 1.0f)
                assertTrue("[$label] 第 $frame 帧（runWithoutImplicitWait）p201 尾部偏差 ${p201TailDriftDp}dp 超过 1dp", p201TailDriftDp <= 1.0f)
            }
            assertTrue("[$label] 逐帧探针必须实际观测到第 1 页合入首帧", mergeFrameObserved)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(expectedMergedCount, vm.uiState.value.floors.size)
        assertEquals(1, vm.uiState.value.firstLoadedPage)
        assertEquals("[$label] 终态首可见楼层 p201 应精准平移至索引 15", 15, listState.firstVisibleItemIndex)
        assertTrue(
            "[$label] 终态滚动偏移必须包含越顶长图高度补偿（before=$scrollOffsetBefore, now=${listState.firstVisibleItemScrollOffset}）",
            listState.firstVisibleItemScrollOffset > scrollOffsetBefore + (400f * density).toInt(),
        )
        assertEquals(0.5f, ImageAspectRatioCache.Default.resolveInitialAspectRatio(tallImageUrl), 0.001f)
    }

    /**
     * 共享逐帧合入校验器（同时服务正式实现与两个反例自证）：
     * 1. 关闭 `mainClock.autoAdvance` 并清空旧样本；
     * 2. 每推进一帧前翻转 `probeStep` 强制 `PrependFrameProbe` 在 `AndroidComposeView.measureAndLayout()` 末尾
     *    `RectManager.dispatchCallbacks()` 处（严格早于 `LaunchedEffect` 恢复与下一帧 `requestLayout`）采样；
     * 3. 每一帧既断言 `PrependFrameProbe` 截获的真实上屏 `anchorOffsetPx` 偏差 `≤ 1dp`，又在
     *    `composeRule.runWithoutImplicitWait` 中断言语义树 `boundsInRoot.top` 偏差 `≤ 1dp`（零隐式 `waitForIdle`）。
     */
    private fun verifyPrependMergeFrameByFrame(
        vm: ThreadDetailViewModel,
        listState: LazyListState,
        anchorKey: String,
        anchorText: String,
        baselineOffsetPx: Int,
        baselineBoundsTopPx: Float,
        expectedMergedCount: Int,
        expectedNewAnchorIndex: Int,
        density: Float,
        probeStep: MutableIntState,
        samples: MutableList<TraversalFrameSample>,
        releaseMerge: () -> Unit,
    ) {
        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            releaseMerge()
            var mergeFrameObserved = false
            for (frame in 1..15) {
                val sampleStart = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val frameSamples = samples.subList(sampleStart, samples.size).toList()
                assertTrue("第 $frame 帧必须捕获到至少 1 条 traversal 末尾探针样本", frameSamples.isNotEmpty())
                for (sample in frameSamples) {
                    val mergedInTraversal = sample.floorsCount == expectedMergedCount &&
                        sample.layoutTotalItemsCount >= expectedMergedCount &&
                        sample.firstLoadedPage == 1
                    if (mergedInTraversal) mergeFrameObserved = true
                    val sampleOffset = sample.anchorOffsetPx
                    assertNotNull(
                        "第 $frame 帧（traversal merged=$mergedInTraversal, firstVisibleKey=${sample.firstVisibleKey}）原首楼已跳出视口",
                        sampleOffset,
                    )
                    val traversalDriftDp = abs(sampleOffset!! - baselineOffsetPx) / density
                    assertTrue(
                        "第 $frame 帧（traversal merged=$mergedInTraversal）原首楼偏移变化 ${traversalDriftDp}dp 超过 1dp（before=$baselineOffsetPx, now=$sampleOffset）",
                        traversalDriftDp <= 1.0f,
                    )
                    if (mergedInTraversal) {
                        assertEquals("合入首帧锚点楼层应精确位于索引 $expectedNewAnchorIndex", expectedNewAnchorIndex, sample.anchorIndex)
                    }
                }

                // 使用 runWithoutImplicitWait 读取语义节点 boundsInRoot，禁止隐式触发 waitForIdle / 下一帧 onLayout
                val currentTop = composeRule.runWithoutImplicitWait {
                    composeRule.onNodeWithText(anchorText, substring = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
                val boundsDriftDp = abs(currentTop - baselineBoundsTopPx) / density
                assertTrue(
                    "第 $frame 帧（runWithoutImplicitWait bounds）原首楼距顶偏差 ${boundsDriftDp}dp 超过 1dp（before=$baselineBoundsTopPx, now=$currentTop）",
                    boundsDriftDp <= 1.0f,
                )
                if (vm.uiState.value.floors.size == expectedMergedCount && vm.uiState.value.firstLoadedPage == 1) {
                    val visibleAnchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }
                    assertNotNull("第 $frame 帧合入后原首可见楼层必须仍在视口内", visibleAnchor)
                    assertEquals(expectedNewAnchorIndex, visibleAnchor!!.index)
                }
            }
            assertTrue("逐帧探针必须实际观测到第 1 页数据合入首帧（samples=$samples）", mergeFrameObserved)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /**
     * 旁路布局探针：借 `Modifier.onLayoutRectChanged(0, 0)` 挂入 `AndroidComposeView.measureAndLayout()`
     * 末尾的 `RectManager.dispatchCallbacks()`（严格在 `dispatchOnPositionedCallbacks()` 之后、`root.draw` 与
     * `LaunchedEffect` 恢复之前），忠实记录每一帧真实上屏瞬间的锚点像素偏移。
     */
    @Composable
    private fun PrependFrameProbe(
        vm: ThreadDetailViewModel,
        listState: LazyListState,
        anchorKey: String,
        probeStep: MutableIntState,
        samples: MutableList<TraversalFrameSample>,
    ) {
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        val heightDp = with(LocalDensity.current) { (if (probeStep.intValue % 2 == 0) 1 else 2).toDp() }
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .height(heightDp)
                .onLayoutRectChanged(debounceMillis = 0L, throttleMillis = 0L) {
                    val layoutInfo = listState.layoutInfo
                    val visible = layoutInfo.visibleItemsInfo
                    val anchorItem = visible.firstOrNull { it.key == anchorKey }
                    val primaryKey = visible.firstOrNull { it.offset + it.size > 0 }?.key
                    samples += TraversalFrameSample(
                        step = probeStep.intValue,
                        floorsCount = uiState.floors.size,
                        layoutTotalItemsCount = layoutInfo.totalItemsCount,
                        firstLoadedPage = uiState.firstLoadedPage,
                        anchorOffsetPx = anchorItem?.offset,
                        anchorIndex = anchorItem?.index,
                        firstVisibleKey = primaryKey,
                    )
                },
        )
    }

    /**
     * 反例 1（仅测试源码用）：用滞后一帧的 `LaunchedEffect(prependGeneration)` 调用 `scrollToItem`
     * （并辅以 `dispatchRawDelta` 把正偏移也拉回原位），验证即便在 `advanceTimeByFrame()` 末尾
     * `scheduler.runCurrent()` 中把锚点完美拉回，合入首帧 traversal 探针仍能精准拦截首帧闪跳。
     */
    @Composable
    private fun CounterexampleLaunchedEffectPane(
        tid: String,
        vm: ThreadDetailViewModel,
        listState: LazyListState,
    ) {
        LaunchedEffect(tid) { vm.bindArgs(ThreadNavArgs(tid = tid, title = "反例 1")) }
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        val lastAnchor = remember { mutableStateOf<Pair<String, Int>?>(null) }
        SideEffect {
            if (uiState.showPrependHeader) {
                val item = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.offset + it.size > 0 && (it.key as? String)?.startsWith("floor:") == true }
                if (item != null) lastAnchor.value = (item.key as String).removePrefix("floor:") to item.offset
            }
        }
        LaunchedEffect(uiState.prependGeneration) {
            if (uiState.prependGeneration == 0L) return@LaunchedEffect
            val (pid, offset) = lastAnchor.value ?: return@LaunchedEffect
            val idx = uiState.floors.indexOfFirst { it.postId == pid }
            if (idx >= 0) {
                listState.scrollToItem(idx, (-offset).coerceAtLeast(0))
                if (offset > 0) listState.dispatchRawDelta(-offset.toFloat())
            }
        }
        val trackAnchorModifier = remember(listState) {
            Modifier.onGloballyPositioned {
                if (vm.uiState.value.showPrependHeader) {
                    val item = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.offset + it.size > 0 && (it.key as? String)?.startsWith("floor:") == true }
                    if (item != null) lastAnchor.value = (item.key as String).removePrefix("floor:") to item.offset
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 60.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.showPrependHeader) {
                item(key = "thread_prepend_header") {
                    HiListFooter(HiFooterState.Loading, modifier = Modifier.widthIn(max = PostMaxContentWidth))
                }
            }
            items(items = uiState.floors, key = { "floor:${it.postId}" }) { item ->
                PostFloorCard(item, item.floor.floor == 1, listState, {}, { _, _ -> }, modifier = trackAnchorModifier)
            }
        }
    }

    /** 反例 2（仅测试源码用）：在 `onGloballyPositioned` 里改调无同步重测的 `requestScrollToItem`（必闪一帧）。 */
    @Composable
    private fun CounterexampleDeferredRequestScrollPane(
        tid: String,
        vm: ThreadDetailViewModel,
        listState: LazyListState,
    ) {
        LaunchedEffect(tid) { vm.bindArgs(ThreadNavArgs(tid = tid, title = "反例 2")) }
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        // 在组合体中显式读取状态快照，确保合入帧触发本函数重组并执行 SideEffect（与生产 ThreadDetailPane 一致）
        val currentUiState = uiState
        val handledGen = remember { mutableStateOf(0L) }
        val pendingTargetIdx = remember { mutableStateOf<Int?>(null) }
        val pendingPositivePx = remember { mutableIntStateOf(0) }

        SideEffect {
            if (currentUiState.prependGeneration != handledGen.value) {
                handledGen.value = currentUiState.prependGeneration
                val anchorItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.offset + it.size > 0 && (it.key as? String)?.startsWith("floor:") == true }
                if (anchorItem != null) {
                    val pid = (anchorItem.key as String).removePrefix("floor:")
                    val newIdx = currentUiState.floors.indexOfFirst { it.postId == pid }
                    if (newIdx >= 0) {
                        listState.requestScrollToItem(newIdx, 0)
                        if (anchorItem.offset > 0) {
                            pendingTargetIdx.value = newIdx
                            pendingPositivePx.intValue = anchorItem.offset
                        }
                    }
                }
            }
        }

        val deferredModifier = remember(listState) {
            Modifier.onGloballyPositioned {
                val targetIdx = pendingTargetIdx.value ?: return@onGloballyPositioned
                val positivePx = pendingPositivePx.intValue
                pendingTargetIdx.value = null
                pendingPositivePx.intValue = 0
                if (targetIdx > 0 && positivePx > 0) {
                    // 故意使用 requestScrollToItem（forceRemeasure=false）替代 dispatchRawDelta，延迟至下一帧
                    listState.requestScrollToItem(targetIdx - 1, positivePx)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 60.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (currentUiState.showPrependHeader) {
                item(key = "thread_prepend_header") {
                    HiListFooter(HiFooterState.Loading, modifier = Modifier.widthIn(max = PostMaxContentWidth))
                }
            }
            items(items = currentUiState.floors, key = { "floor:${it.postId}" }) { item ->
                PostFloorCard(item, item.floor.floor == 1, listState, {}, { _, _ -> }, modifier = deferredModifier)
            }
        }
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

    private class ControllableOfflineImageLoader(private val context: Context) : ImageLoader {
        override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
        override val components: ComponentRegistry = ComponentRegistry()
        override val memoryCache: MemoryCache? = null
        override val diskCache: DiskCache? = null

        val requestedUrls = CopyOnWriteArrayList<String>()
        private val deferreds = LinkedHashMap<String, CompletableDeferred<Pair<Int, Int>>>()

        fun setDeferred(url: String, d: CompletableDeferred<Pair<Int, Int>>) {
            synchronized(this) { deferreds[url] = d }
        }

        override fun enqueue(request: ImageRequest): Disposable = object : Disposable {
            override val job = CompletableDeferred<ImageResult>()
            override val isDisposed = true
            override fun dispose() = Unit
        }

        override suspend fun execute(request: ImageRequest): ImageResult {
            val url = request.data.toString()
            requestedUrls += url
            val d = synchronized(this) { deferreds[url] }
            return if (d != null) {
                val (w, h) = d.await()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                SuccessResult(bmp.asImage(), request, DataSource.NETWORK)
            } else {
                ErrorResult(null, request, RuntimeException("not configured"))
            }
        }

        override fun newBuilder(): ImageLoader.Builder = ImageLoader.Builder(context)
        override fun shutdown() = Unit
    }

    private class ImageMergeThreadRepository(private val tallImageUrl: String) : ThreadDetailRepository {
        @Volatile var firstPageGateArmed = false
        @Volatile var pageInterceptor: (suspend (Int) -> ThreadDetailLoadResult?)? = null

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            pageInterceptor?.invoke(page)?.let { return it }
            return buildPageSuccess(page, tid, filter)
        }

        fun buildPageSuccess(
            page: Int,
            tid: String = "99001",
            filter: ThreadDetailFilter = ThreadDetailFilter.All,
        ): ThreadDetailLoadResult.Success {
            val count = 15
            val startFloor = (page - 1) * 15 + 1
            val floors = (1..count).map { idx ->
                val postId = "p${page}${idx.toString().padStart(2, '0')}"
                val floorNum = startFloor + idx - 1
                val blocks = if (page == 2 && idx == 1) {
                    listOf(
                        ContentBlock.Text(listOf(InlineSpan.Plain("P201 顶部说明文本"))),
                        ContentBlock.Image(fullUrl = tallImageUrl, thumbUrl = tallImageUrl, fileSizeBytes = 4096L),
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain(
                                    "P201_TAIL_ANCHOR：图片下方的第一段正文内容，保持足够高度以跨过视口上缘。\n" +
                                        "第二行：继续填充高度以保证图片越过顶线时 p201 仍为首可见楼层。\n" +
                                        "第三行：用于精确测量尾段锚点相对屏幕顶部的像素偏移。\n" +
                                        "第四行：验证 E2b 与 E2d 两种滚动补偿叠加后的最终一致性。",
                                ),
                            ),
                        ),
                    )
                } else {
                    listOf(
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Plain(
                                    "第 $page 页 $postId 楼层正文，包含多行文字用于区分混合楼层高度，确保前后页合并后首楼定位准确无误。",
                                ),
                            ),
                        ),
                    )
                }
                PostFloor(
                    postId = postId,
                    floor = floorNum,
                    page = page,
                    uid = "${200 + floorNum}",
                    author = "坛友$floorNum",
                    avatarUrl = null,
                    timePost = "2026-11-26 04:00",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = blocks,
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "E2b+E2d 合并探针主题",
                    page = page,
                    lastPage = 3,
                    floors = floors,
                    postsPerPageEstimate = 15,
                    meta = emptyMeta(),
                    hasPostList = true,
                ),
                filter = filter,
                threadAuthorId = "201",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "99001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
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
