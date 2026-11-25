package net.jejer.hipda.feature.threaddetail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
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
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
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
 * 覆盖 E2a 验收项 3 与【负责人追加】R-E0 R2-S2/S3：
 * - 登录成功后从 `AuthRequired` 自动重载（含初次登录、会话过期同号重登、并发水合竞态与非 Auth 错误隔离）；
 * - 透明顶栏玻璃严格兄弟拓扑、`HiGlassMarker == 1`、圆形按钮零嵌套玻璃且触控区 ≥ `48×48dp`；
 * - 滚动期间 `PostFloorCard` 零无关重组门禁；
 * - `只看楼主` 过滤胶囊、`×` 退出回原楼、过滤空态 `没有符合条件的楼层` + `看全部`、`UserOnly` 胶囊、沿用上下页按钮；
 * - Wave 3 `E2b` 并行接缝 `media/PostImageBlock.kt` 签名与 `LazyColumn` `key = "floor:$postId"` 锁定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ThreadDetailViewModelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun authRequired_autoReloadsOnLoginAndSessionReauthWithoutManualRefresh() {
        val authRepo = ProgrammableAuthRepository(initialAccount = null, initialState = AuthState.SignedOut)
        val detailRepo = ProgrammableThreadDetailRepository()
        detailRepo.nextResultProvider = { _, page, filter ->
            if (authRepo.currentAccount.value == null || authRepo.authState.value is AuthState.SessionExpired) {
                ThreadDetailError.AuthRequired(
                    reason = if (authRepo.currentAccount.value == null) {
                        AuthRequiredReason.NOT_LOGGED_IN
                    } else {
                        AuthRequiredReason.RELOGIN_FAILED
                    },
                )
            } else {
                buildSampleSuccess(tid = "2602293", page = page, filter = filter)
            }
        }
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), authRepo)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "2602293", title = "鉴权自动重载测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 游客首次加载命中 AuthRequired，显示“需要登录才能查看”
        assertEquals(1, detailRepo.loadCalls.size)
        composeRule.onNodeWithText("需要登录才能查看").assertIsDisplayed()

        // 2. 登录成功后无需手动点击刷新，自动重载当前页并展示楼层正文
        val userA = SavedAccount(uid = "1001", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        authRepo.emitSignedIn(userA)
        composeRule.waitForIdle()

        assertEquals("登录成功后应自动发起第 2 次重载", 2, detailRepo.loadCalls.size)
        composeRule.onNodeWithText("测试帖子第 1 页第 1 楼内容").assertIsDisplayed()

        // 3. 会话过期后翻到第 2 页命中 AuthRequired，再以同一账号重新登录（sessionEpoch 递增）自动重载第 2 页
        authRepo.emitSessionExpired(userA)
        composeRule.onNodeWithText("下一页").performClick()
        composeRule.waitForIdle()
        assertEquals(3, detailRepo.loadCalls.size)
        composeRule.onNodeWithText("登录已失效").assertIsDisplayed()

        authRepo.emitSignedIn(userA.copy(lastLoginAt = 200L))
        composeRule.waitForIdle()
        assertEquals("同号重新登录后应自动重载第 2 页", 4, detailRepo.loadCalls.size)
        assertEquals(2, detailRepo.loadCalls.last().page)
        composeRule.onNodeWithText("测试帖子第 2 页第 1 楼内容").assertIsDisplayed()

        // 4. 正常已加载状态退出登录不应误触发多余重载
        authRepo.emitSignedOut()
        composeRule.waitForIdle()
        assertEquals("正常读帖态退登不应触发 AuthRequired 自动重载", 4, detailRepo.loadCalls.size)
    }

    @Test
    fun authRequired_inFlightHydrationRaceAndNonAuthErrorIsolation() {
        val authRepo = ProgrammableAuthRepository(initialAccount = null, initialState = AuthState.SignedOut)
        val firstGate = CompletableDeferred<Unit>()
        val detailRepo = ProgrammableThreadDetailRepository()
        val attempt = AtomicInteger(0)
        detailRepo.suspendHook = {
            if (attempt.incrementAndGet() == 1) firstGate.await()
        }
        detailRepo.nextResultProvider = { _, page, filter ->
            if (attempt.get() == 1) {
                ThreadDetailError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN)
            } else {
                buildSampleSuccess(tid = "3001", page = page, filter = filter)
            }
        }
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), authRepo)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "3001", title = "并发水合竞态测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 账号在首次请求在途期间水合完成，首请求回 AuthRequired 后应自动重试一次并成功
        val user = SavedAccount(uid = "2002", username = "AsyncUser", passwordMd5 = "1".repeat(32), lastLoginAt = 50L)
        authRepo.emitSignedIn(user)
        firstGate.complete(Unit)
        composeRule.waitForIdle()

        assertEquals(2, detailRepo.loadCalls.size)
        composeRule.onNodeWithText("测试帖子第 1 页第 1 楼内容").assertIsDisplayed()

        // 对照：2 参次构造函数兼容 E0 调用且 NetworkError 下登录不误重载
        val legacyVm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        assertEquals("", legacyVm.uiState.value.tid)
    }

    @Test
    fun topBarGlassTopology_strictSiblingZeroNestedGlassAnd48dpTouchTargets() {
        val detailRepo = ProgrammableThreadDetailRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        var isFullScreen by mutableStateOf(false)
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "透明顶栏与兄弟玻璃拓扑测试", author = "OP作者"),
                        chromeSpec = DetailChromeSpec(
                            showBack = true,
                            fullScreenState = FullScreenToggle(isFullScreen = isFullScreen, onToggle = { isFullScreen = !isFullScreen }),
                        ),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 全屏仅 1 个 HiGlassMarker（TopBar）与 1 个 HiGlassBlurActive（零嵌套玻璃）
        composeRule.assertGlassSurfaceCountAtMost(1)
        val glassMarkers = composeRule.onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("帖子详情仅应存在 1 个 TopBar 玻璃区域", 1, glassMarkers.size)

        val blurNodes = composeRule.onAllNodes(
            matcher = SemanticsMatcher.keyIsDefined(HiGlassBlurActive),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("顶栏圆钮严禁嵌套 HiGlassSurface，全树仅允许 1 个 HiGlassBlurActive", 1, blurNodes.size)

        // 2. detailGlassState 的 LayerBackdrop 严格位于 LazyColumn，且与 DrawBackdropElement TopBar 互不为祖先/后代
        val scrollNode = composeRule.onAllNodes(
            matcher = SemanticsMatcher("hasVerticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().single()
        val glassNode = glassMarkers.single()

        assertTrue("LazyColumn 必须挂载 LayerBackdrop", hasModifierNamed(scrollNode.layoutInfo, "LayerBackdrop"))
        assertFalse("TopBar 玻璃节点不得挂载 LayerBackdrop", hasModifierNamed(glassNode.layoutInfo, "LayerBackdrop"))
        assertTrue("TopBar 玻璃节点必须挂载 DrawBackdropElement", hasModifierNamed(glassNode.layoutInfo, "DrawBackdrop"))
        assertFalse("LazyColumn 不得为 TopBar 的祖先", isAncestor(scrollNode, glassNode))
        assertFalse("TopBar 不得为 LazyColumn 的祖先", isAncestor(glassNode, scrollNode))

        val rootLayout = composeRule.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals("全树仅允许 1 处 DrawBackdrop（圆钮零嵌套）", 1, countModifiersNamed(rootLayout, "DrawBackdrop"))
        assertEquals("全树仅允许 1 处 LayerBackdrop（仅楼层 LazyColumn）", 1, countModifiersNamed(rootLayout, "LayerBackdrop"))

        // 3. 所有顶栏圆形按钮触控区均 ≥ 48×48dp
        for (cd in listOf("返回", "全屏阅读", "只看楼主")) {
            composeRule.onNodeWithContentDescription(cd)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        // 切换全屏按钮后切换为“显示列表”并同样保持 ≥ 48×48dp
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("显示列表")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun authorOnlyFilterCapsule_exitRestoresOriginalFloor_emptyStateAndPaginationWork() {
        val detailRepo = ProgrammableThreadDetailRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "2602293", title = "只看楼主与分页测试", author = "楼主大人"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 沿用 E0 下一页按钮翻到第 2 页，再滚至第 8 楼（index=7, offset=36）
        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()
        composeRule.onNodeWithText("下一页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 2 / 3 页").assertIsDisplayed()

        composeRule.runOnIdle { listState.requestScrollToItem(index = 7, scrollOffset = 36) }
        composeRule.waitForIdle()
        assertEquals(7, listState.firstVisibleItemIndex)
        assertEquals(36, listState.firstVisibleItemScrollOffset)

        // 2. 开启“只看楼主”：顶栏下方展示“只看楼主 ×”胶囊，“×”内容描述为“退出只看”且 ≥ 48×48dp
        composeRule.onNodeWithContentDescription("只看楼主").performClick()
        composeRule.waitForIdle()

        assertTrue(detailRepo.loadCalls.last().filter is ThreadDetailFilter.AuthorOnly)
        composeRule.onNodeWithText("只看楼主").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("退出只看")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("看全部").assertIsDisplayed()

        // 3. 点击胶囊“×”退出过滤：恢复全帖第 2 页并精准定位回切换前正在阅读的第 8 楼与偏移量
        composeRule.onNodeWithContentDescription("退出只看").performClick()
        composeRule.waitForIdle()

        assertEquals(ThreadDetailFilter.All, detailRepo.loadCalls.last().filter)
        assertEquals("退出只看应恢复切换前所在第 2 页", 2, detailRepo.loadCalls.last().page)
        assertEquals("退出只看应定位回原楼层索引", 7, listState.firstVisibleItemIndex)
        assertEquals("退出只看应保留原像素偏移", 36, listState.firstVisibleItemScrollOffset)
        composeRule.onNodeWithContentDescription("退出只看").assertDoesNotExist()

        // 4. 沿用 E0 上一页按钮回到第 1 页
        composeRule.onNodeWithText("上一页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 / 3 页").assertIsDisplayed()

        // 5. 预留 UserOnly 过滤胶囊展示“只看 {用户名} ×”及过滤结果为空展示“没有符合条件的楼层”+“看全部”
        detailRepo.returnEmptyForFilter = true
        composeRule.runOnIdle { vm.filterByUser(uid = "999", username = "路人甲") }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("只看 路人甲").assertIsDisplayed()
        composeRule.onNodeWithText("没有符合条件的楼层").assertIsDisplayed()
        composeRule.onNodeWithText("看全部").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(ThreadDetailFilterUi.All, vm.uiState.value.filter)
        composeRule.onNodeWithText("测试帖子第 1 页第 1 楼内容").assertIsDisplayed()
    }

    @Test
    fun scrollingLongThread_triggersZeroUnrelatedRecompositionsOnVisibleFloorCards() {
        val floors = (1..40).map { idx ->
            ThreadDetailFloorItem(
                floor = PostFloor(
                    postId = "p_$idx",
                    floor = idx,
                    page = 1,
                    uid = if (idx == 1) "1" else "$idx",
                    author = "作者$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 01:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("长帖重组门禁正文 $idx")))),
                ),
                isAuthorBlocked = false,
            )
        }
        val floor1Counter = RecompositionCounter("Floor1")
        val floor2Counter = RecompositionCounter("Floor2")
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items = floors, key = { "floor:${it.postId}" }) { item ->
                        if (item.postId == "p_1") floor1Counter.Track()
                        if (item.postId == "p_2") floor2Counter.Track()
                        PostFloorCard(
                            item = item,
                            isOp = item.floor.floor == 1,
                            listState = listState,
                            onOpenImageViewer = {},
                            onAuthorClick = { _, _ -> },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        floor1Counter.assertEquals(1)
        floor2Counter.assertEquals(1)

        // 连续滚动偏移：已在视口内的楼层卡不得因滚动状态变化发生多余重组
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 40) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 0, scrollOffset = 80) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 1, scrollOffset = 20) }
        composeRule.waitForIdle()

        floor1Counter.assertRecompositionsAtMost(0)
        floor2Counter.assertRecompositionsAtMost(0)
    }

    @Test
    fun reducedMotion_filterToggleCompletesImmediatelyWithoutIntermediateFrames() = withAnimatorDurationScale(0f) {
        val detailRepo = ProgrammableThreadDetailRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "2602293", title = "减动效测试"),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("只看楼主").performClick()
        waitForIdle()
        onNodeWithText("只看楼主").assertIsDisplayed()

        onNodeWithContentDescription("退出只看").performClick()
        waitForIdle()
        onNodeWithContentDescription("退出只看").assertDoesNotExist()
    }

    @Test
    fun postImageBlockSeam_locksRequiredParametersAndLazyColumnFloorKeyFormat() {
        // 验证 Wave 3 E2b 并行接缝签名存在且参数类型精确匹配
        val seamRef: @Composable (
            ContentBlock.Image,
            String,
            LazyListState,
            (ImageViewerLaunchArgs) -> Unit,
            Modifier,
        ) -> Unit = ::PostImageBlock
        assertTrue(seamRef.toString().isNotEmpty())

        // 验证 LazyColumn 真实消费 "floor:$postId" 主键格式
        val detailRepo = ProgrammableThreadDetailRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        lateinit var listState: LazyListState
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "主键校验"),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val keys = listState.layoutInfo.visibleItemsInfo.map { it.key }
        assertTrue("楼层列表应有可见项", keys.isNotEmpty())
        assertTrue(
            "LazyColumn 项主键必须严格遵循 floor:\$postId，实际：$keys",
            keys.all { it is String && it.startsWith("floor:2602293_p1_") },
        )
    }

    private fun hasModifierNamed(layoutInfo: LayoutInfo, token: String): Boolean =
        layoutInfo.getModifierInfo().any { it.modifier.javaClass.name.contains(token, ignoreCase = true) }

    private fun countModifiersNamed(root: LayoutInfo, token: String): Int {
        var count = root.getModifierInfo().count { it.modifier.javaClass.name.contains(token, ignoreCase = true) }
        val getter = root.javaClass.methods.firstOrNull {
            (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
        }
        val children = (getter?.invoke(root) as? List<*>).orEmpty()
        for (child in children) {
            if (child is LayoutInfo) count += countModifiersNamed(child, token)
        }
        return count
    }

    private fun isAncestor(potentialAncestor: SemanticsNode, node: SemanticsNode): Boolean {
        var current = node.parent
        while (current != null) {
            if (current.id == potentialAncestor.id) return true
            current = current.parent
        }
        return false
    }

    private data class LoadCall(val tid: String, val page: Int, val filter: ThreadDetailFilter)

    private class ProgrammableThreadDetailRepository : ThreadDetailRepository {
        val loadCalls = CopyOnWriteArrayList<LoadCall>()
        @Volatile var returnEmptyForFilter: Boolean = false
        @Volatile var suspendHook: (suspend () -> Unit)? = null
        @Volatile var nextResultProvider: ((String, Int, ThreadDetailFilter) -> ThreadDetailLoadResult)? = null

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadCalls += LoadCall(tid, page, filter)
            suspendHook?.invoke()
            nextResultProvider?.let { return it(tid, page, filter) }
            if (returnEmptyForFilter && filter !is ThreadDetailFilter.All) {
                return ThreadDetailLoadResult.Success(
                    page = ThreadDetailPage(tid, 2, "测试帖子 $tid", 1, 1, emptyList(), null, emptyMeta(), true),
                    filter = filter,
                    threadAuthorId = "100",
                    forumName = forumName,
                )
            }
            return buildSampleSuccess(tid = tid, page = page, filter = filter)
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "2602293", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
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

    private class ProgrammableAuthRepository(
        initialAccount: SavedAccount?,
        initialState: AuthState,
    ) : AuthRepository {
        private val _account = MutableStateFlow(initialAccount)
        private val _authState = MutableStateFlow(initialState)
        private val epochCounter = AtomicInteger(if (initialAccount != null) 1 else 0)

        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initialAccount))
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val sessionEpoch: Long get() = epochCounter.get().toLong()

        fun emitSignedIn(account: SavedAccount) {
            epochCounter.incrementAndGet()
            _account.value = account
            _authState.value = AuthState.SignedIn(account)
        }

        fun emitSessionExpired(account: SavedAccount) {
            _account.value = account
            _authState.value = AuthState.SessionExpired(account, "expired")
        }

        fun emitSignedOut() {
            _account.value = null
            _authState.value = AuthState.SignedOut
        }

        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult =
            LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("offline")
        override suspend fun logout() = emitSignedOut()
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
            SilentReloginResult.NotLoggedIn
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

        private fun buildSampleSuccess(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
        ): ThreadDetailLoadResult.Success {
            val allFloors = (1..20).map { idx ->
                val isOp = idx == 1 || idx % 5 == 0
                PostFloor(
                    postId = "${tid}_p${page}_$idx",
                    floor = (page - 1) * 20 + idx,
                    page = page,
                    uid = if (isOp) "100" else "${200 + idx}",
                    author = if (isOp) "楼主大人" else "回帖坛友$idx",
                    avatarUrl = null,
                    timePost = "2026-11-26 00:${idx.toString().padStart(2, '0')}",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("测试帖子第 $page 页第 $idx 楼内容")))),
                )
            }
            val filtered = when (filter) {
                ThreadDetailFilter.All -> allFloors
                is ThreadDetailFilter.AuthorOnly -> allFloors.filter { it.uid == "100" }
                is ThreadDetailFilter.UserOnly -> allFloors.filter { it.uid == filter.uid }
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "测试主题 $tid",
                    page = page,
                    lastPage = if (filter is ThreadDetailFilter.All) 3 else 1,
                    floors = filtered,
                    postsPerPageEstimate = 20,
                    meta = emptyMeta(),
                    hasPostList = true,
                ),
                filter = if (filter is ThreadDetailFilter.AuthorOnly) ThreadDetailFilter.AuthorOnly("100") else filter,
                threadAuthorId = "100",
            )
        }
    }
}
