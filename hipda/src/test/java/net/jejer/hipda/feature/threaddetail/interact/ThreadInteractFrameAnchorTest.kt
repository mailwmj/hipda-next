package net.jejer.hipda.feature.threaddetail.interact

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.PostFloorCard
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
 * 修复轮 1 正式逐帧锚定与跳楼落定测试套件（沿用 E2d `PrependFrameProbe` 模式，100% 离线）：
 * 原样引入 `V-E2c` 4 项阻断探针（F1、F2-A、F2-B、F3），并逐一配对反例实现自证测试必红：
 * 1. F1：跨页跳楼合入首帧即落目标楼且同帧高亮生效 + 上方图片变高不漂移（含延迟 `LaunchedEffect` 反例）；
 * 2. F2-A：退出 `只看此人` 回原楼保留非零偏移 `-24px`，合入首帧起逐帧零跳动（含延迟 `LaunchedEffect` 反例）；
 * 3. F2-B：从非首条命中楼退出 `只看此人`，合入首帧直接回原楼不闪第 1 命中楼（含延迟 `LaunchedEffect` 反例）；
 * 4. F3：跳楼落定窗口内上方视口内图片 `4:3 -> 1:3` 变高由 `PrependAnchorController` 补偿保目标不位移（含无落定窗口反例与用户手动滚动立即解除窗口用例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ThreadInteractFrameAnchorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        context = ApplicationProvider.getApplicationContext()
        val cm = context.getSystemService(ConnectivityManager::class.java)
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
    fun tearDown() {
        ImageAspectRatioCache.Default.clear()
    }

    // region F1: 跨页跳楼合入首帧直接落位 + 同帧高亮 + 上方图片变高

    @Test
    fun f1_crossPageJump_withGrowingImageAboveTarget_landsOnMergeFrameWithHighlightAndZeroDrift() {
        verifyCrossPageJumpFromMergeFrame(useCounterexamplePane = false, tid = "7101")
    }

    @Test
    fun f1_counterexample_deferredLaunchedEffectPendingScroll_mustFailOnMergeFrame() {
        assertThrows(AssertionError::class.java) {
            verifyCrossPageJumpFromMergeFrame(useCounterexamplePane = true, tid = "7102")
        }
    }

    private fun verifyCrossPageJumpFromMergeFrame(useCounterexamplePane: Boolean, tid: String) {
        val tallImgUrl = "https://img.example.invalid/f1_above_target_${tid}.jpg"
        val imageGate = CompletableDeferred<Pair<Int, Int>>()
        val imageLoader = ControllableProbeImageLoader(context).apply { setDeferred(tallImgUrl, imageGate) }

        val page2Gate = CompletableDeferred<Unit>()
        val repo = ProbeThreadDetailRepository(
            page1 = buildPageWithLongQuoteAndJump(tid = tid, targetPid = "p_target_16"),
            page2 = buildGapNumberedPage2(
                tid = tid,
                targetIndex = 15,
                targetPid = "p_target_16",
                targetFloorNum = 35,
                aboveTargetImageUrl = tallImgUrl,
            ),
        ).apply {
            gateForPostId["p_target_16"] = page2Gate
        }
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), FakeAuthRepository(loggedInAccount("100", "linus_hi")))
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<FrameSample>()
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                        listState = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            if (useCounterexamplePane) {
                                CounterexampleDeferredPendingScrollPane(
                                    tid = tid,
                                    vm = vm,
                                    listState = listState,
                                    delayHighlightUntilConsume = true,
                                )
                            } else {
                                ThreadDetailPane(
                                    args = ThreadNavArgs(tid = tid, title = "跨页跳楼上方图片变高探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    listState = listState,
                                    viewModel = vm,
                                )
                            }
                            PrependFrameProbe(vm, listState, "floor:p_target_16", probeStep, samples)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("回复 35# 缺号目标作者").performClick()
        composeRule.waitForIdle()
        assertTrue("跨页跳楼请求应在途", vm.uiState.value.isLoading)

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            page2Gate.complete(Unit)
            var mergeFrameIndex: Int? = null
            var landedTopPx: Float? = null
            var landedOffsetPx: Int? = null
            var imageReleased = false
            var highlightSeen = false

            for (frame in 1..25) {
                val startIdx = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val frameSamples = samples.subList(startIdx, samples.size).toList()
                assertTrue("第 $frame 帧必须捕获 traversal 探针样本", frameSamples.isNotEmpty())
                val s = frameSamples.last()

                if (mergeFrameIndex == null && s.page == 2 && s.floorsCount == 20) {
                    mergeFrameIndex = frame
                    assertNotNull(
                        "【合入首帧 frame=$frame 失败】目标楼层 floor:p_target_16 未在数据合入首帧落入视口！visibleKeys=${s.visibleKeys}, firstVisible=${s.firstVisibleKey}, highlighted=${s.highlightedPostId}",
                        s.targetOffsetPx,
                    )
                    assertEquals(
                        "【合入首帧 frame=$frame 失败】目标楼层未落在预期偏移 0px，实际 offset=${s.targetOffsetPx}",
                        0,
                        s.targetOffsetPx,
                    )
                    assertEquals(
                        "【合入首帧 frame=$frame 失败】目标楼层高亮必须在合入首帧同步生效",
                        "p_target_16",
                        s.highlightedPostId,
                    )
                }

                if (mergeFrameIndex != null) {
                    val targetTopNow = composeRule.runWithoutImplicitWait {
                        composeRule.onAllNodesWithText("35楼", useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .firstOrNull()
                            ?.boundsInRoot?.top
                    }
                    assertNotNull("第 $frame 帧目标楼层 35楼 必须保持在视口可见", targetTopNow)
                    if (landedTopPx == null) {
                        landedTopPx = targetTopNow
                        landedOffsetPx = s.targetOffsetPx
                    } else {
                        val driftDp = abs(targetTopNow!! - landedTopPx) / density
                        assertTrue(
                            "第 $frame 帧目标楼层 35楼 距预期位置偏移 ${driftDp}dp 超过 1dp（expected=$landedTopPx, now=$targetTopNow）",
                            driftDp <= 1.0f,
                        )
                    }
                    if (!imageReleased && imageLoader.requestedUrls.contains(tallImgUrl)) {
                        imageReleased = true
                        imageGate.complete(400 to 1200)
                    }
                    val hasHighlight = composeRule.runWithoutImplicitWait {
                        composeRule.onAllNodes(
                            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位到此楼层"),
                        ).fetchSemanticsNodes().isNotEmpty()
                    }
                    if (hasHighlight) highlightSeen = true
                }
            }

            assertNotNull("必须观测到第 2 页数据合入帧", mergeFrameIndex)
            assertTrue("必须观测到目标楼层高亮可见", highlightSeen)
            assertTrue("目标楼层上方图片应被加载并完成变高", imageReleased)
            assertEquals(1f / 3f, ImageAspectRatioCache.Default.get(tallImgUrl)!!, 0.001f)
            assertNotNull("最终目标楼层仍在视口预期位置", landedOffsetPx)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    // endregion

    // region F3: 跳楼落定窗口内上方视口内图片变高补偿

    @Test
    fun f3_jumpToNearBottomFloor_withVisibleImageGrowingAboveTarget_keepsTargetAnchoredEveryFrame() {
        verifyVisibleAboveImageGrowthAfterJump(useCounterexamplePane = false, tid = "7103")
    }

    @Test
    fun f3_counterexample_withoutLandingAnchorWindow_mustFailWhenVisibleImageAboveTargetGrows() {
        assertThrows(AssertionError::class.java) {
            verifyVisibleAboveImageGrowthAfterJump(useCounterexamplePane = true, tid = "7104")
        }
    }

    private fun verifyVisibleAboveImageGrowthAfterJump(useCounterexamplePane: Boolean, tid: String) {
        val tallImgUrl = "https://img.example.invalid/f3_in_viewport_above_${tid}.jpg"
        val imageGate = CompletableDeferred<Pair<Int, Int>>()
        val imageLoader = ControllableProbeImageLoader(context).apply { setDeferred(tallImgUrl, imageGate) }

        val repo = ProbeThreadDetailRepository(
            page1 = buildPageWithLongQuoteAndJump(tid = tid, targetPid = "p_target_18"),
            page2 = buildGapNumberedPage2(
                tid = tid,
                targetIndex = 17,
                targetPid = "p_target_18",
                targetFloorNum = 38,
                aboveTargetImageUrl = tallImgUrl,
            ),
        )
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), FakeAuthRepository(loggedInAccount("100", "linus_hi")))
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<FrameSample>()
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                        listState = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            if (useCounterexamplePane) {
                                CounterexampleDeferredPendingScrollPane(
                                    tid = tid,
                                    vm = vm,
                                    listState = listState,
                                    delayHighlightUntilConsume = false,
                                )
                            } else {
                                ThreadDetailPane(
                                    args = ThreadNavArgs(tid = tid, title = "上方可见图片变高探针"),
                                    chromeSpec = DetailChromeSpec(showBack = true),
                                    onBack = {},
                                    onOpenLogin = {},
                                    listState = listState,
                                    viewModel = vm,
                                )
                            }
                            PrependFrameProbe(vm, listState, "floor:p_target_18", probeStep, samples)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.jumpToFloor("p_target_18") }
        composeRule.waitForIdle()

        val targetBeforeTop = composeRule.onAllNodesWithText("38楼", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        val targetBeforeOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "floor:p_target_18" }?.offset
        assertNotNull("跳楼落位后 38楼 应在视口内", targetBeforeTop)
        assertNotNull("跳楼落位后 floor:p_target_18 应在 visibleItemsInfo 中", targetBeforeOffset)
        assertTrue("前提：上方带图楼层应发起图片加载", imageLoader.requestedUrls.contains(tallImgUrl))

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            imageGate.complete(400 to 1200)
            for (frame in 1..25) {
                val startIdx = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val s = samples.subList(startIdx, samples.size).last()
                assertNotNull(
                    "第 $frame 帧上方可见图片变高期间 floor:p_target_18 不得被挤出视口（visible=${s.visibleKeys}）",
                    s.targetOffsetPx,
                )
                val frameDriftDp = abs(s.targetOffsetPx!! - targetBeforeOffset!!) / density
                assertTrue(
                    "第 $frame 帧目标楼层偏移 ${frameDriftDp}dp 超过 1dp（before=$targetBeforeOffset, now=${s.targetOffsetPx}）",
                    frameDriftDp <= 1.0f,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        val targetVisibleAfter = listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:p_target_18" }
        val targetAfterTop = composeRule.onAllNodesWithText("38楼", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        val displacementDp = if (targetAfterTop != null) abs(targetAfterTop - targetBeforeTop!!) / density else Float.MAX_VALUE
        assertTrue(
            "上方可见图片变高后目标楼层发生显著位移/被挤出视口：visibleAfter=$targetVisibleAfter, beforeTop=$targetBeforeTop, afterTop=$targetAfterTop, displacement=${displacementDp}dp",
            targetVisibleAfter && displacementDp <= 1.0f,
        )
    }

    @Test
    fun f3_landingAnchorWindow_terminatesImmediatelyOnUserManualScroll() {
        val tallImgUrl = "https://img.example.invalid/f3_user_scroll_release.jpg"
        val imageGate = CompletableDeferred<Pair<Int, Int>>()
        val imageLoader = ControllableProbeImageLoader(context).apply { setDeferred(tallImgUrl, imageGate) }
        val repo = ProbeThreadDetailRepository(
            page1 = buildPageWithLongQuoteAndJump(tid = "7105", targetPid = "p_target_16"),
            page2 = buildGapNumberedPage2(
                tid = "7105",
                targetIndex = 15,
                targetPid = "p_target_16",
                targetFloorNum = 35,
                aboveTargetImageUrl = tallImgUrl,
            ),
        )
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), FakeAuthRepository(loggedInAccount("100", "linus_hi")))
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalHiImageLoader provides imageLoader) {
                        listState = rememberLazyListState()
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "7105", title = "落定窗口手动滚动解除"),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            listState = listState,
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { vm.jumpToFloor("p_target_16") }
        composeRule.waitForIdle()
        val indexAfterJump = listState.firstVisibleItemIndex
        val offsetAfterJump = listState.firstVisibleItemScrollOffset

        // 用户手动上滑：落定窗口立即解除，绝不得将用户滚回原跳楼锚点
        composeRule.onNode(
            SemanticsMatcher("hasVerticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config },
        ).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val movedAfterUserSwipe = listState.firstVisibleItemIndex > indexAfterJump ||
            (listState.firstVisibleItemIndex == indexAfterJump && listState.firstVisibleItemScrollOffset > offsetAfterJump)
        assertTrue(
            "用户首次手动滚动后落定窗口必须立即释放，保持用户滚到的位置（before=($indexAfterJump,$offsetAfterJump), after=(${listState.firstVisibleItemIndex},${listState.firstVisibleItemScrollOffset})）",
            movedAfterUserSwipe,
        )
    }

    // endregion

    // region F2: 退出「只看此人」合入首帧直接回原楼（保留偏移 + 非首条命中楼层）

    @Test
    fun f2a_userOnlyFilter_exitReturnsToOriginalFloorWithOffset_zeroJumpEveryFrameFromMerge() {
        verifyFilterExitWithOffset(useCounterexamplePane = false, tid = "7111")
    }

    @Test
    fun f2a_counterexample_deferredLaunchedEffectFilterExitOffset_mustFailOnMergeFrame() {
        assertThrows(AssertionError::class.java) {
            verifyFilterExitWithOffset(useCounterexamplePane = true, tid = "7112")
        }
    }

    private fun verifyFilterExitWithOffset(useCounterexamplePane: Boolean, tid: String) {
        val page1 = buildUserOnlyFilterPage(tid = tid, title = "只看此人回原楼逐帧探针")
        val repo = ProbeThreadDetailRepository(page1 = page1, page2 = page1)
        val auth = FakeAuthRepository(loggedInAccount("100", "linus_hi"))
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), auth)
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<FrameSample>()
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        if (useCounterexamplePane) {
                            CounterexampleDeferredPendingScrollPane(
                                tid = tid,
                                vm = vm,
                                listState = listState,
                                delayHighlightUntilConsume = false,
                            )
                        } else {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = tid, title = "只看此人回原楼逐帧探针"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                listState = listState,
                                viewModel = vm,
                            )
                        }
                        PrependFrameProbe(vm, listState, "floor:p_8", probeStep, samples)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { listState.requestScrollToItem(index = 7, scrollOffset = 24) }
        composeRule.waitForIdle()
        val p8OffsetBeforeFilter = listState.layoutInfo.visibleItemsInfo.first { it.key == "floor:p_8" }.offset

        if (useCounterexamplePane) {
            composeRule.runOnIdle { vm.filterByUser("808", "target_user", anchorPostId = "p_8", anchorScrollOffset = 24) }
            composeRule.waitForIdle()
        } else {
            composeRule.onNodeWithText("第 8 楼内容", substring = true).performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("只看此人").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("只看 target_user").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("退出只看").assertIsDisplayed()
        }
        assertEquals(2, vm.uiState.value.floors.size)

        val restoreAllGate = CompletableDeferred<Unit>()
        repo.gateForAllReload = restoreAllGate
        if (useCounterexamplePane) {
            composeRule.runOnIdle { vm.clearFilter() }
        } else {
            composeRule.onNodeWithContentDescription("退出只看").performClick()
        }
        composeRule.waitForIdle()

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            restoreAllGate.complete(Unit)
            var mergeFrameIndex: Int? = null
            var baselineRestoredTopPx: Float? = null

            for (frame in 1..20) {
                val startIdx = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()

                val frameSamples = samples.subList(startIdx, samples.size).toList()
                assertTrue("第 $frame 帧必须捕获 traversal 样本", frameSamples.isNotEmpty())
                val s = frameSamples.last()

                if (s.floorsCount == 20) {
                    if (mergeFrameIndex == null) {
                        mergeFrameIndex = frame
                        assertEquals(
                            "【回原楼合入首帧 frame=$frame 失败】首可见项必须直接回到 floor:p_8，不得先闪出 floor:p_1！actual=${s.firstVisibleKey}, visible=${s.visibleKeys}",
                            "floor:p_8",
                            s.firstVisibleKey,
                        )
                        assertEquals(
                            "【回原楼合入首帧 frame=$frame 失败】p_8 偏移必须与进入过滤前一致",
                            p8OffsetBeforeFilter,
                            s.targetOffsetPx,
                        )
                    }
                    val p8TopNow = composeRule.runWithoutImplicitWait {
                        composeRule.onAllNodesWithText("8楼", useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .firstOrNull()
                            ?.boundsInRoot?.top
                    }
                    assertNotNull("第 $frame 帧 8楼 必须可见", p8TopNow)
                    if (baselineRestoredTopPx == null) {
                        baselineRestoredTopPx = p8TopNow
                    } else {
                        val driftDp = abs(p8TopNow!! - baselineRestoredTopPx) / density
                        assertTrue("第 $frame 帧回原楼后发生跳动（drift=${driftDp}dp > 1dp）", driftDp <= 1.0f)
                    }
                    assertEquals("floor:p_8", s.firstVisibleKey)
                }
            }
            assertNotNull("必须观测到退出过滤全帖合入帧", mergeFrameIndex)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun f2b_userOnlyFilter_exitFromNonFirstMatchingFloor_returnsToOriginalFloorFromMergeFrame() {
        verifyFilterExitFromNonFirstFloor(useCounterexamplePane = false, tid = "7113")
    }

    @Test
    fun f2b_counterexample_deferredLaunchedEffectNonFirstFloorExit_mustFailOnMergeFrame() {
        assertThrows(AssertionError::class.java) {
            verifyFilterExitFromNonFirstFloor(useCounterexamplePane = true, tid = "7114")
        }
    }

    private fun verifyFilterExitFromNonFirstFloor(useCounterexamplePane: Boolean, tid: String) {
        val page1 = buildUserOnlyFilterPage(tid = tid, title = "非首命中楼只看此人回原楼探针")
        val repo = ProbeThreadDetailRepository(page1 = page1, page2 = page1)
        val auth = FakeAuthRepository(loggedInAccount("100", "linus_hi"))
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), auth)
        lateinit var listState: LazyListState
        val probeStep = mutableIntStateOf(0)
        val samples = mutableListOf<FrameSample>()

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        if (useCounterexamplePane) {
                            CounterexampleDeferredPendingScrollPane(
                                tid = tid,
                                vm = vm,
                                listState = listState,
                                delayHighlightUntilConsume = false,
                            )
                        } else {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = tid, title = "非首命中楼只看此人回原楼探针"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                listState = listState,
                                viewModel = vm,
                            )
                        }
                        PrependFrameProbe(vm, listState, "floor:p_15", probeStep, samples)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { listState.requestScrollToItem(index = 14, scrollOffset = 0) }
        composeRule.waitForIdle()

        if (useCounterexamplePane) {
            composeRule.runOnIdle { vm.filterByUser("808", "target_user", anchorPostId = "p_15", anchorScrollOffset = 0) }
            composeRule.waitForIdle()
        } else {
            composeRule.onNodeWithText("第 15 楼内容", substring = true).performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("只看此人").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(2, vm.uiState.value.floors.size)

        val restoreAllGate = CompletableDeferred<Unit>()
        repo.gateForAllReload = restoreAllGate
        if (useCounterexamplePane) {
            composeRule.runOnIdle { vm.clearFilter() }
        } else {
            composeRule.onNodeWithContentDescription("退出只看").performClick()
        }
        composeRule.waitForIdle()

        samples.clear()
        composeRule.mainClock.autoAdvance = false
        try {
            restoreAllGate.complete(Unit)
            var mergeFrameIndex: Int? = null
            for (frame in 1..15) {
                val startIdx = samples.size
                probeStep.intValue += 1
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                val s = samples.subList(startIdx, samples.size).last()
                if (s.floorsCount == 20 && mergeFrameIndex == null) {
                    mergeFrameIndex = frame
                    assertEquals(
                        "【合入首帧 frame=$frame 失败】退出只看后首帧必须直接回到 floor:p_15，不得先闪出 floor:p_8！actualFirst=${s.firstVisibleKey}, visible=${s.visibleKeys}",
                        "floor:p_15",
                        s.firstVisibleKey,
                    )
                }
            }
            assertNotNull(mergeFrameIndex)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    // endregion

    // region Counterexample Pane & PrependFrameProbe & Fakes

    /**
     * 测试专用反例面板：刻意复现旧版滞后一帧的 `LaunchedEffect(pendingScroll?.id)` 跳楼/回原楼行为
     * 且不启用 `PrependAnchorController` 的落定窗口锚定，用以自证 F1 / F2-A / F2-B / F3 四套探针均可稳定拦截回归。
     */
    @Composable
    private fun CounterexampleDeferredPendingScrollPane(
        tid: String,
        vm: ThreadDetailViewModel,
        listState: LazyListState,
        delayHighlightUntilConsume: Boolean,
    ) {
        LaunchedEffect(tid) { vm.bindArgs(ThreadNavArgs(tid = tid, title = "反例面板")) }
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        val pending = uiState.pendingScroll
        LaunchedEffect(pending?.id, uiState.floors) {
            if (pending == null || uiState.isLoading) return@LaunchedEffect
            val idx = pending.postId?.let { pid -> uiState.floors.indexOfFirst { it.postId == pid }.takeIf { it >= 0 } } ?: 0
            listState.scrollToItem(idx, pending.scrollOffset.coerceAtLeast(0))
            vm.consumePendingScroll(pending.id)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 56.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items = uiState.floors, key = { "floor:${it.postId}" }) { item ->
                val hlToken = if (delayHighlightUntilConsume && uiState.pendingScroll != null) {
                    null
                } else {
                    uiState.highlightedFloor?.takeIf { it.postId == item.postId }?.tokenId
                }
                PostFloorCard(
                    item = item,
                    isOp = item.floor.floor == 1,
                    listState = listState,
                    onOpenImageViewer = {},
                    onAuthorClick = { _, _ -> },
                    onJumpToFloor = vm::jumpToFloor,
                    highlightToken = hlToken,
                    onHighlightFinished = vm::onHighlightFinished,
                )
            }
        }
    }

    private data class FrameSample(
        val step: Int,
        val floorsCount: Int,
        val page: Int,
        val visibleKeys: List<Any>,
        val targetOffsetPx: Int?,
        val firstVisibleKey: Any?,
        val highlightedPostId: String?,
    )

    @Composable
    private fun PrependFrameProbe(
        vm: ThreadDetailViewModel,
        listState: LazyListState,
        targetKey: String,
        probeStep: MutableIntState,
        samples: MutableList<FrameSample>,
    ) {
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        val heightDp = with(LocalDensity.current) { (if (probeStep.intValue % 2 == 0) 1 else 2).toDp() }
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .height(heightDp)
                .onLayoutRectChanged(debounceMillis = 0L, throttleMillis = 0L) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val targetItem = visible.firstOrNull { it.key == targetKey }
                    val primaryKey = visible.firstOrNull { it.offset + it.size > 0 }?.key
                    samples += FrameSample(
                        step = probeStep.intValue,
                        floorsCount = uiState.floors.size,
                        page = uiState.page,
                        visibleKeys = visible.map { it.key },
                        targetOffsetPx = targetItem?.offset,
                        firstVisibleKey = primaryKey,
                        highlightedPostId = uiState.highlightedFloor?.postId,
                    )
                },
        )
    }

    private fun buildUserOnlyFilterPage(tid: String, title: String): ThreadDetailPage {
        val floors = (1..20).map { idx ->
            PostFloor(
                postId = "p_$idx",
                floor = idx,
                page = 1,
                uid = if (idx == 8 || idx == 15) "808" else "${100 + idx}",
                author = if (idx == 8 || idx == 15) "target_user" else "other_$idx",
                avatarUrl = null,
                timePost = "2026-11-26 05:${idx.toString().padStart(2, '0')}",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = listOf(
                    ContentBlock.Text(
                        listOf(InlineSpan.Plain("第 $idx 楼内容：用于验证只看此人后退出过滤回到原楼层。\n第二行填充高度。\n第三行填充高度。")),
                    ),
                ),
            )
        }
        return ThreadDetailPage(
            tid = tid, fid = 2, title = title, page = 1, lastPage = 2,
            floors = floors, postsPerPageEstimate = 20,
            meta = DiscuzPageMeta(DiscuzSessionInfo(true, "100", "linus_hi"), "fh", null, PromptCounts()),
            hasPostList = true,
        )
    }

    private fun buildPageWithLongQuoteAndJump(tid: String, targetPid: String): ThreadDetailPage {
        val floors = (1..20).map { idx ->
            val blocks = when (idx) {
                1 -> listOf(
                    ContentBlock.Text(listOf(InlineSpan.Plain("1楼开篇正文"))),
                    ContentBlock.GoToFloor("回复 35# 缺号目标作者", tid, targetPid, 35, "缺号目标作者"),
                )
                else -> listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 1 页第 $idx 楼正文"))))
            }
            PostFloor(
                postId = "p1_$idx",
                floor = idx,
                page = 1,
                uid = if (idx == 1) "100" else "${200 + idx}",
                author = if (idx == 1) "linus_hi" else "坛友$idx",
                avatarUrl = null,
                timePost = "2026-11-26 09:${idx.toString().padStart(2, '0')}",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = blocks,
            )
        }
        return ThreadDetailPage(
            tid = tid, fid = 2, title = "跳楼探针帖", page = 1, lastPage = 2,
            floors = floors, postsPerPageEstimate = 20,
            meta = DiscuzPageMeta(DiscuzSessionInfo(true, "100", "linus_hi"), "fh", null, PromptCounts()),
            hasPostList = true,
        )
    }

    private fun buildGapNumberedPage2(
        tid: String,
        targetIndex: Int,
        targetPid: String,
        targetFloorNum: Int,
        aboveTargetImageUrl: String? = null,
    ): ThreadDetailPage {
        val floors = (0 until 20).map { idx ->
            val pid = if (idx == targetIndex) targetPid else "p2_${idx + 1}"
            val floorNum = when {
                idx < targetIndex -> 21 + idx
                idx == targetIndex -> targetFloorNum
                else -> targetFloorNum + (idx - targetIndex) + 2
            }
            val blocks = when {
                aboveTargetImageUrl != null && idx == targetIndex - 1 -> listOf(
                    ContentBlock.Text(listOf(InlineSpan.Plain("目标楼层上一楼顶部文本"))),
                    ContentBlock.Image(fullUrl = aboveTargetImageUrl, thumbUrl = aboveTargetImageUrl, fileSizeBytes = 8192L),
                    ContentBlock.Text(listOf(InlineSpan.Plain("目标楼层上一楼尾段文本"))),
                )
                idx == targetIndex -> listOf(
                    ContentBlock.Text(
                        listOf(InlineSpan.Plain("这是跨页缺号目标楼层（postId=$targetPid，floor=$targetFloorNum）。\n填充高度行 2\n填充高度行 3\n填充高度行 4")),
                    ),
                )
                else -> listOf(
                    ContentBlock.Text(
                        listOf(InlineSpan.Plain("第 2 页索引 $idx 楼层（${floorNum}楼）正文，多行高度填充确保目标楼层位于首屏外且滚顶落位。\n第 2 行。\n第 3 行。\n第 4 行。")),
                    ),
                )
            }
            PostFloor(
                postId = pid,
                floor = floorNum,
                page = 2,
                uid = if (idx == targetIndex) "350" else "${300 + idx}",
                author = if (idx == targetIndex) "缺号目标作者" else "二页坛友$idx",
                avatarUrl = null,
                timePost = "2026-11-26 10:${idx.toString().padStart(2, '0')}",
                isWarned = false,
                postStatus = null,
                signature = null,
                poll = null,
                blocks = blocks,
            )
        }
        return ThreadDetailPage(
            tid = tid, fid = 2, title = "跳楼探针帖", page = 2, lastPage = 2,
            floors = floors, postsPerPageEstimate = 20,
            meta = DiscuzPageMeta(DiscuzSessionInfo(true, "100", "linus_hi"), "fh", null, PromptCounts()),
            hasPostList = true,
        )
    }

    private class ProbeThreadDetailRepository(
        private val page1: ThreadDetailPage,
        private val page2: ThreadDetailPage,
    ) : ThreadDetailRepository {
        val gateForPostId = mutableMapOf<String, CompletableDeferred<Unit>>()
        var gateForAllReload: CompletableDeferred<Unit>? = null

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            if (filter is ThreadDetailFilter.All && gateForAllReload != null) {
                val g = gateForAllReload
                gateForAllReload = null
                g?.await()
            }
            val base = if (page <= 1) page1 else page2
            val filteredFloors = when (filter) {
                ThreadDetailFilter.All -> base.floors
                is ThreadDetailFilter.AuthorOnly -> base.floors.filter { it.uid == (filter.authorId ?: "100") }
                is ThreadDetailFilter.UserOnly -> base.floors.filter { it.uid == filter.uid }
            }
            return ThreadDetailLoadResult.Success(
                page = base.copy(floors = filteredFloors, lastPage = if (filter is ThreadDetailFilter.All) base.lastPage else 1),
                filter = filter,
                threadAuthorId = "100",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, page1.lastPage, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun loadByPostId(
            pid: String,
            tid: String?,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            gateForPostId.remove(pid)?.await()
            val targetPage = if (page2.floors.any { it.postId == pid }) page2 else page1
            return ThreadDetailLoadResult.Success(
                page = targetPage,
                filter = ThreadDetailFilter.All,
                threadAuthorId = "100",
                targetPostId = pid,
            )
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            ThreadDetailError.NetworkError("unused")
    }

    private class FakeAuthRepository(initial: SavedAccount?) : AuthRepository {
        private val accountFlow = MutableStateFlow(initial)
        private val accountsFlow = MutableStateFlow(listOfNotNull(initial))
        private val stateFlow = MutableStateFlow<AuthState>(
            if (initial != null) AuthState.SignedIn(initial) else AuthState.SignedOut,
        )
        override val sessionEpoch: Long = 1L
        override val currentAccount: StateFlow<SavedAccount?> = accountFlow
        override val savedAccounts: StateFlow<List<SavedAccount>> = accountsFlow
        override val authState: StateFlow<AuthState> = stateFlow
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() = Unit
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class ControllableProbeImageLoader(private val context: Context) : ImageLoader {
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

    private fun loggedInAccount(uid: String, username: String) = SavedAccount(
        uid = uid,
        username = username,
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 1_000L,
    )

    private fun createIdleGateway() = SiteAccessGateway(
        client = OkHttpClient(),
        detector = ChallengeDetector(),
        solver = ChallengeSolver { SolveResult.Cancelled },
    )

    // endregion
}
