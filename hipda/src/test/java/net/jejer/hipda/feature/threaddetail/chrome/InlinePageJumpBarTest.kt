package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E4 验收项 4（内联跳页条）：
 * 1. `< 480dp` 紧凑双行 / `≥ 480dp` 单行排布；
 * 2. `340dp` + `1.25×` 零挤压（滑块 `weight(1f)` 无硬编码最小宽）且全控件触控区 `≥ 48dp`；
 * 3. 滑块拖动仅更新本地预览与输入（零滚屏、零发网），`首页`/`末页` 边界禁用，动态 `关闭`/`跳转`；
 * 4. 模拟 IME 弹出仅上移跳页条，底层楼层 `LazyColumn` 偏移变化严格 `== 0dp`；
 * 5. 任何跳转先压 `PositionStack` 并自动收起；`关闭`/空白点击/系统返回收起不跳；
 * 6. TalkBack 播报、减动效 0ms 及展开/收起中途反向点击 `assertFrameContinuity` 逐帧连续。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class InlinePageJumpBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactBelow480dpIsTwoRow_andWideAtLeast480dpIsSingleRow() {
        var containerWidthDp by mutableStateOf(411.dp)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.width(containerWidthDp)) {
                    InlinePageJumpBar(
                        currentPage = 3,
                        lastPage = 12,
                        containerWidth = containerWidthDp,
                        onJumpToPage = {},
                        onCollapse = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // < 480dp：首页/末页与预览在第 1 行，滑块与输入框在第 2 行
        val firstBoundsCompact = composeRule.onNodeWithText("首页").fetchSemanticsNode().boundsInRoot
        val sliderBoundsCompact = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText("第 3 / 12 页").assertIsDisplayed()
        assertTrue(
            "< 480dp 应采用双行排布（首页位于滑块上方）：first=${firstBoundsCompact.bottom} slider=${sliderBoundsCompact.top}",
            firstBoundsCompact.center.y < sliderBoundsCompact.top,
        )

        // ≥ 480dp（先切宽再复测）
        containerWidthDp = 520.dp
        composeRule.waitForIdle()
        val firstBoundsWide = composeRule.onNodeWithText("首页").fetchSemanticsNode().boundsInRoot
        val sliderBoundsWide = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNode().boundsInRoot
        val closeBoundsWide = composeRule.onNodeWithContentDescription("关闭").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "≥ 480dp 应采用单行排布（首页、滑块、关闭垂直居中对齐）",
            abs(firstBoundsWide.center.y - sliderBoundsWide.center.y) < 12f &&
                abs(sliderBoundsWide.center.y - closeBoundsWide.center.y) < 12f,
        )
    }

    @Test
    @Config(qualifiers = "w340dp-h800dp-xhdpi")
    fun narrow340dpWith125FontScale_zeroSquishAndAllControlsAtLeast48dp() {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.25f)) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val glassState = rememberHiGlassState()
                    Box(Modifier.width(340.dp).fillMaxSize()) {
                        ThreadBottomToolbar(
                            page = 4,
                            lastPage = 45,
                            isFavorited = false,
                            isAttention = false,
                            glassState = glassState,
                            suppressBottomGlass = false,
                            isJumpBarExpanded = true,
                            onExpandedChange = {},
                            onReplyClick = {},
                            onJumpToPage = {},
                            onToggleFavorite = {},
                            onRefreshClick = {},
                            onToggleAttention = {},
                            onCopyLinkClick = {},
                            onOpenInBrowserClick = {},
                            onShareClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val maxRightPx = with(density) { 340.dp.toPx() + 1f }

        val firstNode = composeRule.onNodeWithText("首页").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
        val lastNode = composeRule.onNodeWithText("末页").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
        val sliderNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
        val inputNode = composeRule.onNodeWithContentDescription("页码").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
        val closeNode = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()

        // R-Dkudo：滑块无硬编码最小宽，在 340dp + 1.25× 下仍保有充裕自适应宽度且同行零重叠/零越界
        val sliderWidthDp = with(density) { sliderNode.boundsInRoot.width.toDp() }
        assertTrue("340dp + 1.25× 下滑块自适应宽度应充裕（> 60dp），实际 $sliderWidthDp", sliderWidthDp > 60.dp)
        assertTrue("首页与末页不得重叠：first=${firstNode.boundsInRoot} last=${lastNode.boundsInRoot}", firstNode.boundsInRoot.right <= lastNode.boundsInRoot.left)
        assertTrue("滑块与输入框不得重叠：slider=${sliderNode.boundsInRoot} input=${inputNode.boundsInRoot}", sliderNode.boundsInRoot.right <= inputNode.boundsInRoot.left + 1f)
        assertTrue("输入框与关闭按钮不得重叠：input=${inputNode.boundsInRoot} close=${closeNode.boundsInRoot}", inputNode.boundsInRoot.right <= closeNode.boundsInRoot.left + 1f)
        assertTrue("关闭按钮右缘不得溢出 340dp 容器：${closeNode.boundsInRoot.right} > $maxRightPx", closeNode.boundsInRoot.right <= maxRightPx)
    }

    @Test
    fun sliderLocalPreviewOnly_boundaryButtonsDisable_andDynamicCloseGoAction() {
        val repo = JumpTestRepository(totalPages = 10)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8101", title = "滑块本地预览与边界按钮测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, repo.requestedPages.size)
        val scrollIndexBefore = listState.firstVisibleItemIndex
        val scrollOffsetBefore = listState.firstVisibleItemScrollOffset

        // 展开内联跳页条：验证展开无障碍播报与边界按钮（第 1 页首页禁用、末页可用）
        composeRule.onNodeWithContentDescription("第 1 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()

        val expandedAnnouncement = composeRule.onNodeWithContentDescription("已展开跳页，当前第 1 页，共 10 页").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, expandedAnnouncement.config[SemanticsProperties.LiveRegion])
        assertTrue(
            "R-E4 S2：无障碍播报节点不得为 0.dp 尺寸，实际 bounds=${expandedAnnouncement.boundsInRoot}",
            expandedAnnouncement.boundsInRoot.width > 0f && expandedAnnouncement.boundsInRoot.height > 0f,
        )
        composeRule.onNodeWithText("首页").assertIsNotEnabled()
        composeRule.onNodeWithText("末页").assertIsEnabled()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()

        // 拖动滑块至第 6 页：仅更新本地预览、无障碍 stateDescription 与输入框，零滚屏、零发网，动作按钮切为「跳转」
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        composeRule.waitForIdle()

        assertEquals("拖动滑块严禁触发网络请求", 1, repo.requestedPages.size)
        assertEquals("拖动滑块严禁滚动底层列表索引", scrollIndexBefore, listState.firstVisibleItemIndex)
        assertEquals("拖动滑块严禁滚动底层列表偏移", scrollOffsetBefore, listState.firstVisibleItemScrollOffset)
        composeRule.onNodeWithText("第 6 / 10 页").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("跳转").assertIsDisplayed()
        val sliderStateDesc = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("第 6 / 10 页", sliderStateDesc)

        // 输入越界页码 99 -> 动态按钮退回「关闭」；输入有效变更页码 7 -> 切为「跳转」
        val input = composeRule.onNodeWithContentDescription("页码")
        input.performTextClearance()
        input.performTextInput("99")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()

        input.performTextClearance()
        input.performTextInput("7")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("跳转").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 跳转后自动收起并已压入 PositionStack
        assertEquals(7, vm.uiState.value.page)
        assertTrue(vm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithContentDescription("第 7 页，共 10 页，点按展开跳页").assertIsDisplayed()

        // 跳至末页（第 10 页）后展开验证：首页可用、末页禁用
        composeRule.onNodeWithContentDescription("第 7 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        assertEquals(10, vm.uiState.value.page)

        composeRule.onNodeWithContentDescription("第 10 页，共 10 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("首页").assertIsEnabled()
        composeRule.onNodeWithText("末页").assertIsNotEnabled()
    }

    @Test
    fun imeInsets_liftsJumpBarOnly_underlyingLazyColumnOffsetDeltaStrictlyZeroDp() {
        val repo = JumpTestRepository(totalPages = 8)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8102", title = "IME 零滚底隔离测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 先滚至第 4 楼偏移 36px，再展开内联跳页条
        composeRule.runOnIdle { listState.requestScrollToItem(index = 3, scrollOffset = 36) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 页，共 8 页，点按展开跳页").performClick()
        composeRule.waitForIdle()

        val floorTopBefore = composeRule.onNodeWithText("第 1 页第 5 楼正文", substring = true).fetchSemanticsNode().boundsInRoot.top
        val jumpBarBottomBefore = composeRule.onNodeWithContentDescription("页码").fetchSemanticsNode().boundsInRoot.bottom
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset

        // 向 Compose 宿主视图（LocalView）分发 280dp 高度的 IME WindowInsets
        val imeHeightPx = with(composeRule.density) { 280.dp.roundToPx() }
        composeRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeightPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
        }
        composeRule.waitForIdle()

        val floorTopAfter = composeRule.onNodeWithText("第 1 页第 5 楼正文", substring = true).fetchSemanticsNode().boundsInRoot.top
        val jumpBarBottomAfter = composeRule.onNodeWithContentDescription("页码").fetchSemanticsNode().boundsInRoot.bottom
        val floorDriftDp = with(composeRule.density) { abs(floorTopAfter - floorTopBefore).toDp() }
        val jumpLiftDp = with(composeRule.density) { (jumpBarBottomBefore - jumpBarBottomAfter).toDp() }

        assertTrue("IME 弹出后跳页条自身应显著上移（实测上移 $jumpLiftDp）", jumpLiftDp > 200.dp)
        assertEquals("IME 弹出期间底层 LazyColumn 偏移变化必须严格为 0dp", 0f, floorDriftDp.value, 0.01f)
        assertEquals("IME 弹出期间底层首可见索引不得变化", indexBefore, listState.firstVisibleItemIndex)
        assertEquals("IME 弹出期间底层像素偏移不得变化", offsetBefore, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun dismissActionsCollapseWithoutJumping_andImeGoJumpsAndCollapses() {
        val repo = JumpTestRepository(totalPages = 6)
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var hostBackDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "8103", title = "收起与回车跳页测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 点击关闭收起不跳页
        composeRule.onNodeWithContentDescription("第 1 页，共 6 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 2. 系统返回收起不跳页
        composeRule.onNodeWithContentDescription("第 1 页，共 6 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 页，共 6 页，点按展开跳页").assertIsDisplayed()
        assertEquals(1, vm.uiState.value.page)

        // 3. 输入页码 4 后触发键盘 Go 动作：压栈、跳至第 4 页并自动收起
        composeRule.onNodeWithContentDescription("第 1 页，共 6 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        val input = composeRule.onNodeWithContentDescription("页码")
        input.performTextClearance()
        input.performTextInput("4")
        input.performImeAction()
        composeRule.waitForIdle()

        assertEquals(4, vm.uiState.value.page)
        assertTrue(vm.uiState.value.canPopPositionStack)
        composeRule.onNodeWithContentDescription("第 4 页，共 6 页，点按展开跳页").assertIsDisplayed()
    }

    @Test
    fun reducedMotionSnapsInOneFrame_andMidFlightReversalIsFrameContinuous() {
        // 1. 减动效 0ms：展开一帧到位
        withAnimatorDurationScale(0f) {
            var expanded by mutableStateOf(false)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val glassState = rememberHiGlassState()
                        Box(Modifier.fillMaxSize()) {
                            ThreadBottomToolbar(
                                page = 2, lastPage = 10, isFavorited = false, isAttention = false,
                                glassState = glassState, suppressBottomGlass = false,
                                isJumpBarExpanded = expanded, onExpandedChange = { expanded = it },
                                onReplyClick = {}, onJumpToPage = {}, onToggleFavorite = {},
                                onRefreshClick = {}, onToggleAttention = {}, onCopyLinkClick = {},
                                onOpenInBrowserClick = {}, onShareClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            expanded = true
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("关闭").assertIsDisplayed()
            onNodeWithText("首页").assertIsDisplayed()
        }

        // 2. 展开中途反向点击收起：中段胶囊位置逐帧连续可打断，平滑弹回原位
        withAnimatorDurationScale(1f) {
            var expanded by mutableStateOf(false)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        val glassState = rememberHiGlassState()
                        Box(Modifier.fillMaxSize()) {
                            ThreadBottomToolbar(
                                page = 2, lastPage = 10, isFavorited = false, isAttention = false,
                                glassState = glassState, suppressBottomGlass = false,
                                isJumpBarExpanded = expanded, onExpandedChange = { expanded = it },
                                onReplyClick = {}, onJumpToPage = {}, onToggleFavorite = {},
                                onRefreshClick = {}, onToggleAttention = {}, onCopyLinkClick = {},
                                onOpenInBrowserClick = {}, onShareClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 中段 HiGlassSurface 是节点列表中最后一个挂载的 HiGlassBlurActive（展开/收起全程持久存活）
            val middleGlassNode = {
                onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).let { list ->
                    list[list.fetchSemanticsNodes().lastIndex]
                }
            }
            val startPos = middleGlassNode().fetchSemanticsNode().positionInRoot

            val reversal = assertFrameContinuity(
                nodeProvider = middleGlassNode,
                frameCount = 24,
                maxStepDp = 36.dp,
                minTotalTravelDp = 12.dp,
                onFrame = { frame ->
                    if (frame == 0) expanded = true
                    else if (frame == 3) expanded = false
                },
            )
            val endPos = reversal.positionsPx.last()
            assertTrue(
                "中途反向打断后应平滑回落原位：start=$startPos end=$endPos",
                (endPos - startPos).getDistance() < with(density) { 4.dp.toPx() },
            )
        }
    }

    private class JumpTestRepository(private val totalPages: Int) : ThreadDetailRepository {
        val requestedPages = CopyOnWriteArrayList<Int>()
        override suspend fun loadPage(
            tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean,
            forumName: String?, priority: AccessPriority,
        ): ThreadDetailLoadResult {
            val resolved = page.coerceIn(1, totalPages)
            requestedPages += resolved
            val floors = (1..12).map { idx ->
                PostFloor(
                    "jp${resolved}_$idx", (resolved - 1) * 12 + idx, resolved, "1001", "作者$idx",
                    null, "2026-11-26 10:00", false, null, null, null,
                    listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 $resolved 页第 $idx 楼正文内容，用于验证 IME 弹出时底层不滚动。")))),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid, 2, "内联跳页测试主题", resolved, totalPages, floors, 12,
                    DiscuzPageMeta(DiscuzSessionInfo(true, "1001", "作者1"), "fh1", null, PromptCounts()), true,
                ),
                filter = filter,
                threadAuthorId = "1001",
            )
        }
        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, totalPages)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "8101", 1)
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
        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
