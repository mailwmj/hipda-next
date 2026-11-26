package net.jejer.hipda.feature.imageviewer

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.ColorImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 负责人额外加测探针（`E14 @ cee7791` + `main @ 4b6c1f0` 合并集成验证）：
 * 1. E2b 帖子列表上方图片变高导致看图期间缩略图下移，退出必须飞回当前新位置（同时测组件级 `resolveBoundsForIndex` 与真实 `NextAppRoot + ThreadDetailPane + ImageViewerRendererImpl` 链路）；
 * 2. 看图浮层退场动画进行中连按两次返回，底层帖子不得被关闭（同时测「第 1 次 Back 启动退场 + 退场期第 2 次 Back」与「已处于退场动画期连按两次 Back」）；
 * 3. `933dp` 三栏下打开看图，浮层盖住全部三栏且全屏同屏玻璃数为 0。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerShellMergeProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
    }

    @After
    fun tearDown() {
        ImageAspectRatioCache.Default.clear()
    }

    @Test
    fun probeM1a_e2bUpperImageGrowsDuringViewing_overlayWithLiveResolverExitsToNewBounds() {
        val upperUrl = "https://img.example.invalid/m1a_upper_tall.jpg"
        val lowerUrl = "https://img.example.invalid/m1a_lower_thumb.jpg"
        val upperGate = CompletableDeferred<coil3.Image>()
        val loader = ProbeImageLoader { req ->
            when (req.data.toString()) {
                upperUrl -> SuccessResult(upperGate.await(), req, DataSource.NETWORK)
                else -> SuccessResult(ColorImage(0xFF336699.toInt(), 400, 300), req, DataSource.MEMORY)
            }
        }

        var liveLowerBounds: Rect? = null
        var viewerArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)
        var dismissCount = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            item(key = "floor:p1") {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("第1楼")
                                    Spacer(Modifier.height(8.dp))
                                    PostImageBlock(
                                        image = ContentBlock.Image(upperUrl, upperUrl, 1024L),
                                        floorPostId = "p1",
                                        listState = listState,
                                        onOpenImageViewer = {},
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                                            if (coords.isAttached) {
                                                val tl = coords.positionInWindow()
                                                liveLowerBounds = Rect(tl, Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
                                            }
                                        },
                                    ) {
                                        PostImageBlock(
                                            image = ContentBlock.Image(lowerUrl, lowerUrl, 1024L),
                                            floorPostId = "p1",
                                            listState = listState,
                                            onOpenImageViewer = { args -> viewerArgs = args },
                                        )
                                    }
                                }
                            }
                        }

                        viewerArgs?.let { activeArgs ->
                            ImageViewerOverlay(
                                args = activeArgs,
                                onDismiss = {
                                    dismissCount++
                                    viewerArgs = null
                                },
                                originalDownloader = IdleProbeDownloader(),
                                mediaActions = RecordingMediaActions(),
                                resolveBoundsForIndex = { idx -> if (idx == 0) liveLowerBounds else null },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val oldBounds = requireNotNull(liveLowerBounds)

        // 点击下方第 2 张缩略图进入看图，等待入场完成
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and hasClickAction(),
            useUnmergedTree = true,
        ).onLast().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

        // 看图期间释放上方较长图（400x500 -> 高由 ~568px 增至 ~947.5px），使下方缩略图明显下移且仍在视口内可见
        upperGate.complete(ColorImage(0xFF884422.toInt(), 400, 500))
        composeRule.waitForIdle()
        val newBounds = requireNotNull(liveLowerBounds)
        val density = composeRule.density.density
        assertTrue(
            "上方图片变高后缩略图必须明显下移且仍处于窗口内：old=${oldBounds}, new=${newBounds}",
            newBounds.top - oldBounds.top > 100f * density && newBounds.bottom <= 891f * density,
        )

        // 逐帧退出，验证必须飞回当前新位置 newBounds（误差 ≤ 1dp），而不是 oldBounds
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("关闭").performClick()
        var lastRect: Rect? = null
        for (f in 0..150) {
            if (dismissCount > 0) break
            val nodes = composeRule.onAllNodes(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) lastRect = nodes.first().boundsInWindow
            composeRule.mainClock.advanceTimeByFrame()
        }
        assertEquals(1, dismissCount)
        assertRectNear("组件级传 live resolver 时必须飞回当前新缩略图位置", newBounds, requireNotNull(lastRect), density)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun probeM1b_realNextAppRootAndThreadDetailPane_upperE2bImageGrowsDuringViewing_mustExitToNewThumbnailPosition() {
        val upperUrl = "https://img.example.invalid/m1b_upper_tall.jpg"
        val lowerUrl = "https://img.example.invalid/m1b_lower_thumb.jpg"
        val upperGate = CompletableDeferred<coil3.Image>()
        val loader = ProbeImageLoader { req ->
            when (req.data.toString()) {
                upperUrl -> SuccessResult(upperGate.await(), req, DataSource.NETWORK)
                else -> SuccessResult(ColorImage(0xFF336699.toInt(), 400, 300), req, DataSource.MEMORY)
            }
        }
        val repo = TwoImageThreadDetailRepository(upperUrl, lowerUrl)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(IdleProbeDownloader(), RecordingMediaActions())
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "E2b+E14几何探针帖", 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val thumbMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            !hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))
        val oldThumbBounds = composeRule.onAllNodes(thumbMatcher, useUnmergedTree = true).onLast().fetchSemanticsNode().boundsInWindow

        // 点击下方第 2 张缩略图打开真实 ImageViewerRendererImpl
        composeRule.onAllNodes(thumbMatcher, useUnmergedTree = true).onLast().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

        // 看图期间释放上方较长图（400x450），让底层 ThreadDetailPane 中的下方缩略图位置在窗口内明显下移且 100% 不越出底边
        upperGate.complete(ColorImage(0xFF884422.toInt(), 400, 450))
        composeRule.waitForIdle()
        val newThumbBounds = composeRule.onAllNodes(thumbMatcher, useUnmergedTree = true).onLast().fetchSemanticsNode().boundsInWindow
        val density = composeRule.density.density
        assertTrue(
            "底层 ThreadDetailPane 缩略图位置必须发生真实下移且仍在视口内：old=$oldThumbBounds, new=$newThumbBounds",
            newThumbBounds.top - oldThumbBounds.top > 50f * density && newThumbBounds.bottom <= 891f * density,
        )

        // 逐帧退出看图，核查末帧几何是否飞回当前新位置 newThumbBounds（不能飞回旧位置 oldThumbBounds）
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("关闭").performClick()
        var lastRect: Rect? = null
        for (f in 0..150) {
            val nodes = composeRule.onAllNodes(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isEmpty()) break
            lastRect = nodes.first().boundsInWindow
            composeRule.mainClock.advanceTimeByFrame()
        }
        val finalExitRect = requireNotNull(lastRect) { "未采集到真实集成看图退场末帧" }
        assertRectNear(
            "真实 NextAppRoot+ThreadDetailPane+E14 退出必须飞回缩略图当前新位置（而不是旧位置 $oldThumbBounds）",
            newThumbBounds,
            finalExitRect,
            density,
        )
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun probeM3a_backStartsExitThenSecondBackDuringExit_doesNotCloseUnderlyingThread() {
        val imageUrl = "https://img.example.invalid/m3a.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF336699.toInt(), 400, 300), req, DataSource.MEMORY)
        }
        val repo = SingleImageRepo(imageUrl)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(IdleProbeDownloader(), RecordingMediaActions())
        var backDispatcher: OnBackPressedDispatcher? = null
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "退场连按测试帖", 2))

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        openThreadImageViewer()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

        // 第 1 次按返回启动退场动画，退场进行中按第 2 次返回（命中 E-slots 兜底 BackHandler）
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertTrue("看图浮层应已关闭", composeRule.onAllNodes(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("底层帖子 1001 绝不能被关掉", "1001", harness.hostViewModel.state.value.selectedThread?.tid)
    }

    @Test
    fun probeM3b_whileExitAnimationInProgress_doublePressBack_doesNotCloseUnderlyingThread() {
        val imageUrl = "https://img.example.invalid/m3b.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF336699.toInt(), 400, 300), req, DataSource.MEMORY)
        }
        val repo = SingleImageRepo(imageUrl)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(IdleProbeDownloader(), RecordingMediaActions())
        var backDispatcher: OnBackPressedDispatcher? = null
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "退场期双连按测试帖", 2))

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        openThreadImageViewer()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

        // 启动退场动画（点关闭按钮进入 Dismissing 阶段），确保退场动画正在进行
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue(
            "退场第 1 帧时看图浮层仍在播放退场动画",
            composeRule.onAllNodes(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )

        // 严格按需求：退场动画进行中连按两次返回，底下的帖子不能被关掉
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "看图浮层退场动画进行中连按两次返回后，底下的帖子 1001 必须仍保持打开",
            "1001",
            harness.hostViewModel.state.value.selectedThread?.tid,
        )
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun probeM4_threePane933dp_openViewerCoversAllThreePanesAndZeroGlassOnScreen() {
        val imageUrl = "https://img.example.invalid/m4_933.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF336699.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        val repo = SingleImageRepo(imageUrl)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(IdleProbeDownloader(), RecordingMediaActions())
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "933dp三栏看图帖", 2))

        composeRule.setContent {
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                        imageLoader = loader,
                        imageViewerRenderer = renderer,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 打开看图前处于 933dp 三栏，底层存在玻璃（中栏顶栏 + 右栏顶栏）
        val preOpenGlass = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("933dp 三栏看图前应存在底层液态玻璃区域，实际 $preOpenGlass", preOpenGlass >= 2)

        openThreadImageViewer()
        composeRule.waitForIdle()

        // 1) 验证看图浮层完整覆盖全部三栏（933dp x 696dp）
        val density = composeRule.density.density
        val overlayNode = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图")).fetchSemanticsNode()
        val overlayBounds = overlayNode.boundsInWindow
        assertEquals("933dp 三栏下看图左边界必须为 0", 0f, overlayBounds.left, density)
        assertEquals("933dp 三栏下看图顶边界必须为 0", 0f, overlayBounds.top, density)
        assertEquals("933dp 三栏下看图必须盖满全部三栏宽度 933dp", 933f * density, overlayBounds.width, density)
        assertEquals("933dp 三栏下看图必须盖满窗口高度 696dp", 696f * density, overlayBounds.height, density)

        // 2) 验证看图打开时全屏同屏玻璃数为 0
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    // ==================== 辅助函数 ====================

    private fun openThreadImageViewer() {
        val thumbMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            !hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))
        composeRule.onAllNodes(thumbMatcher, useUnmergedTree = true).onFirst().performClick()
    }

    private fun viewerImageMatcher(desc: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasContentDescription(desc) and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))

    private fun assertRectNear(msg: String, expected: Rect, actual: Rect, tolerancePx: Float) {
        assertTrue(
            "$msg: expected=$expected, actual=$actual, tol=${tolerancePx}px",
            abs(expected.left - actual.left) <= tolerancePx &&
                abs(expected.top - actual.top) <= tolerancePx &&
                abs(expected.right - actual.right) <= tolerancePx &&
                abs(expected.bottom - actual.bottom) <= tolerancePx,
        )
    }

    private fun ownerWithThreadDetailRepo(
        harness: MultiPaneTestHarness,
        repo: ThreadDetailRepository,
    ): androidx.lifecycle.ViewModelStoreOwner {
        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(repo, harness.gateway) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }
    }
}

private class SingleImageRepo(private val imageUrl: String) : ThreadDetailRepository {
    override suspend fun loadPage(
        tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val floor = PostFloor(
            postId = "${tid}_1", floor = 1, page = page, uid = "1", author = "贴图楼主", avatarUrl = null,
            timePost = "2026-11-26 08:00", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Text(listOf(InlineSpan.Plain("测试看图帖子正文"))),
                ContentBlock.Image(fullUrl = imageUrl, thumbUrl = imageUrl, fileSizeBytes = 1024L),
            ),
        )
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(tid, 2, "测试帖子标题 $tid", page, 1, listOf(floor), 1, offlinePageMeta(), true),
            filter = filter, forumName = forumName, threadAuthorId = "1",
        )
    }

    override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid, 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
    override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
    override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
        floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
    override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
    override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
    override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
    override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
        throw UnsupportedOperationException()
}

private class TwoImageThreadDetailRepository(
    private val upperUrl: String,
    private val lowerUrl: String,
) : ThreadDetailRepository {
    override suspend fun loadPage(
        tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val floor = PostFloor(
            postId = "${tid}_1", floor = 1, page = page, uid = "1", author = "贴图楼主", avatarUrl = null,
            timePost = "2026-11-26 08:00", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Text(listOf(InlineSpan.Plain("上方图片加载期间变高，下方缩略图下移"))),
                ContentBlock.Image(fullUrl = upperUrl, thumbUrl = upperUrl, fileSizeBytes = 2048L),
                ContentBlock.Image(fullUrl = lowerUrl, thumbUrl = lowerUrl, fileSizeBytes = 1024L),
            ),
        )
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(tid, 2, "测试帖子标题 $tid", page, 1, listOf(floor), 1, offlinePageMeta(), true),
            filter = filter, forumName = forumName, threadAuthorId = "1",
        )
    }

    override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid, 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
    override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
    override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
        floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
    override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
    override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
    override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
    override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
        throw UnsupportedOperationException()
}
