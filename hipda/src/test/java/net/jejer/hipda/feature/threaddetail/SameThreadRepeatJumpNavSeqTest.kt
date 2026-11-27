package net.jejer.hipda.feature.threaddetail

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.OfflineAuthRepository
import net.jejer.hipda.feature.app.OfflineSearchRepository
import net.jejer.hipda.feature.app.OfflineThreadListRepository
import net.jejer.hipda.feature.app.RecordingSettingsRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalPostLinkDispatcher
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.routing.DeepLinkTarget
import net.jejer.hipda.feature.routing.DefaultPostLinkDispatcher
import net.jejer.hipda.feature.routing.ExternalLinkOpener
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 覆盖 M7-P2 b 项（R-M7-G3 S1）：
 * 1. 点正文原始 `<a href="...pid=X">` 跳楼 -> Back -> 再点同一链接 -> 再次跳到该楼；
 * 2. 长按「直达末页」在末楼 pid 未变时 -> Back -> 再次长按「直达末页」 -> 再次跳到末楼；
 * 3. 旋转重建（ViewModel 保留、UI 重建以相同 `navSeq` 重入 `bindArgs`）绝不重复跳转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SameThreadRepeatJumpNavSeqTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sameThreadSameLinkAndLastPage_repeatJumpAfterBack_andRotationDoesNotRepeatJump() {
        val repo = RepeatJumpThreadRepository()
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(repo, gateway)
        val hostVm = NextAppHostViewModel(RecordingSettingsRepository(AppSettings()), repo)
        hostVm.openThread(ThreadNavArgs(tid = "88100", title = "同帖重复跳楼测试", fid = 2))

        val linkDispatcher = DefaultPostLinkDispatcher(
            forumServerProvider = { "https://www.4d4y.com" },
            onOpenForum = {},
            onOpenThread = { tid, page -> hostVm.applyDeepLink(DeepLinkTarget.Thread(tid, page)) },
            onOpenThreadLastPage = { tid -> hostVm.applyDeepLink(DeepLinkTarget.ThreadLastPage(tid)) },
            onOpenPost = { tid, pid -> hostVm.applyDeepLink(DeepLinkTarget.Post(tid, pid)) },
            onOpenUserProfile = {},
            externalLinkOpener = ExternalLinkOpener { false },
        )

        var hostBackDispatcher: OnBackPressedDispatcher? = null
        var rotationGeneration by mutableIntStateOf(0)
        lateinit var listState: LazyListState

        composeRule.setContent {
            val appState by hostVm.state.collectAsState()
            val selected = requireNotNull(appState.selectedThread)
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalPostLinkDispatcher provides linkDispatcher) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    // key(rotationGeneration) 模拟屏幕旋转后 Composable 树重建但 ViewModel 保活的场景
                    key(rotationGeneration) {
                        listState = rememberLazyListState()
                        ThreadDetailPane(
                            args = selected,
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
        assertTrue("初始应停留在 1 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse("前提：第 18 楼初始应在视口外", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" })

        // 1. 首次点击正文原始链接 pid=1018 -> 跳至 18 楼并压 PositionStack
        val seqBeforeFirstClick = hostVm.state.value.selectedThread!!.navSeq
        composeRule.onNodeWithText("跳转到同帖第18楼链接").performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        val seqAfterFirstClick = hostVm.state.value.selectedThread!!.navSeq
        assertTrue("每次跳楼 navSeq 必须严格递增", seqAfterFirstClick > seqBeforeFirstClick)
        assertEquals("1018", hostVm.state.value.selectedThread?.targetPid)
        assertTrue("第 1 次点击应落位至 floor:1018", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" })
        assertTrue(vm.uiState.value.canPopPositionStack)

        // 2. 按 Back 返回 1 楼，PositionStack 清空
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue("Back 后应回到 1 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse("Back 后 PositionStack 应为空", vm.uiState.value.canPopPositionStack)

        // 3. 此时模拟旋转重建（selectedThread 仍带 targetPid=1018 与相同 navSeq）-> 不得重复跳回 18 楼
        rotationGeneration += 1
        composeRule.waitForIdle()
        assertTrue("旋转重建后必须停留在 1 楼，不得重放旧跳楼参数", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse("旋转重建不得重新压入 PositionStack", vm.uiState.value.canPopPositionStack)

        // 4. 再次点击同一条正文原始链接 pid=1018 -> 因 navSeq 递增必须再次跳至 18 楼
        composeRule.onNodeWithText("跳转到同帖第18楼链接").performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        val seqAfterSecondClick = hostVm.state.value.selectedThread!!.navSeq
        assertTrue("再次点击同一链接 navSeq 必须继续递增", seqAfterSecondClick > seqAfterFirstClick)
        assertTrue("第 2 次点击同一链接必须再次跳至 floor:1018", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" })
        assertTrue("第 2 次跳楼必须再次压入 PositionStack", vm.uiState.value.canPopPositionStack)

        // 返回 1 楼
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 5. 多栏长按「直达末页」（末楼 pid=3020）-> Back -> 再次长按（末楼 pid 未变）-> 再次跳至末楼 -> Back + 旋转不重跳
        hostVm.openThread(ThreadNavArgs(tid = "88100", title = "同帖重复跳楼测试", openLastPage = true))
        awaitCondition { hostVm.state.value.selectedThread?.targetPid == "3020" }
        composeRule.waitForIdle()
        val seqAfterFirstLastPage = hostVm.state.value.selectedThread!!.navSeq
        assertEquals(3, vm.uiState.value.page)
        assertTrue("第 1 次直达末页应落位 floor:3020", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" })
        assertTrue(vm.uiState.value.canPopPositionStack)

        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 再次长按直达末页（末楼 pid 仍为 3020）
        hostVm.openThread(ThreadNavArgs(tid = "88100", title = "同帖重复跳楼测试", openLastPage = true))
        awaitCondition { hostVm.state.value.selectedThread!!.navSeq > seqAfterFirstLastPage }
        composeRule.waitForIdle()
        assertEquals(3, vm.uiState.value.page)
        assertTrue("末楼 pid 未变时再次长按直达末页必须再次跳至 floor:3020", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" })
        assertTrue(vm.uiState.value.canPopPositionStack)

        // 退回 1 楼后旋转重建 -> 不得重跳至第 3 页末楼
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertFalse(vm.uiState.value.canPopPositionStack)

        val loadByPidBeforeRotation = repo.loadByPidCalls.size
        rotationGeneration += 1
        composeRule.waitForIdle()
        assertEquals("旋转重建不得重新触发末页跳转", 1, vm.uiState.value.page)
        assertEquals("旋转重建不得多发 loadByPostId 请求", loadByPidBeforeRotation, repo.loadByPidCalls.size)
        assertFalse(vm.uiState.value.canPopPositionStack)
    }

    /** R-M7-P2 S1 ①：进程被杀从保存态重建（navSeq == 0L）且 Room 已有阅读进度时，优先恢复阅读进度，不重放 targetPid / openLastPage。 */
    @Test
    fun s1_processDeathRestore_withSavedReadingPosition_prefersReadingPositionOverTargetPidAndOpenLastPage() {
        val savedPosition = ReadingPositionEntity(
            tid = "88100",
            page = 2,
            pid = "2008",
            offsetPx = 36,
            filterMode = ReadingFilterMode.NONE,
            updatedAt = 1_700_000_000_000L,
        )
        val repoWithPid = RepeatJumpThreadRepository(savedPosition = savedPosition)
        val repoWithLastPage = RepeatJumpThreadRepository(savedPosition = savedPosition)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vmTargetPid = ThreadDetailViewModel(repoWithPid, gateway)
        val vmOpenLast = ThreadDetailViewModel(repoWithLastPage, gateway)
        var subCase by mutableIntStateOf(0)
        lateinit var listState1: LazyListState
        lateinit var listState2: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                if (subCase == 0) {
                    listState1 = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "88100", title = "进程重建优先阅读进度", fid = 2, targetPid = "3020", navSeq = 0L),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState1,
                        viewModel = vmTargetPid,
                    )
                } else {
                    listState2 = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "88100", title = "进程重建末页标记", fid = 2, openLastPage = true, navSeq = 0L),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState2,
                        viewModel = vmOpenLast,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("保存态恢复且有阅读进度时应落在已保存的第 2 页，而非重放 targetPid=3020", 2, vmTargetPid.uiState.value.page)
        assertEquals("保存态恢复且有阅读进度时不得调用 loadByPostId 重放 targetPid", emptyList<String>(), repoWithPid.loadByPidCalls)
        assertEquals("应恢复至第 2 页第 8 楼（索引 7）", 7, listState1.firstVisibleItemIndex)
        assertEquals("应恢复原始像素偏移 36", 36, listState1.firstVisibleItemScrollOffset)

        // openLastPage = true 且 navSeq == 0L 同样优先恢复 Room 阅读进度
        subCase = 1
        composeRule.waitForIdle()
        assertEquals("保存态恢复且有阅读进度时应落在第 2 页，而非重放 openLastPage", 2, vmOpenLast.uiState.value.page)
        assertEquals("不得调用 loadLastPage 重放 openLastPage", 0, repoWithLastPage.loadLastPageCalls)
        assertEquals("应恢复至第 2 页第 8 楼（索引 7）", 7, listState2.firstVisibleItemIndex)
        assertEquals("应恢复原始像素偏移 36", 36, listState2.firstVisibleItemScrollOffset)
    }

    /** R-M7-P2 S1 ②：保存态恢复（navSeq == 0L）但 Room 无阅读进度时，保持现有行为跳至 targetPid。 */
    @Test
    fun s1_processDeathRestore_withoutSavedReadingPosition_fallsBackToTargetPid() {
        val repo = RepeatJumpThreadRepository(savedPosition = null)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(repo, gateway)
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88100", title = "无进度保存态恢复", fid = 2, targetPid = "3020", navSeq = 0L),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf("3020"), repo.loadByPidCalls)
        assertEquals(3, vm.uiState.value.page)
        assertTrue("无保存阅读进度时应回退跳转至 floor:3020", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" })
    }

    /** R-M7-P2 S1 ③：用户点击产生的新跳转（navSeq > 0L），即使 Room 已有阅读进度，仍直接跳至 targetPid。 */
    @Test
    fun s1_newUserClickJump_withPositiveNavSeq_overridesSavedReadingPositionAndJumpsToTargetPid() {
        val savedPosition = ReadingPositionEntity(
            tid = "88100",
            page = 2,
            pid = "2008",
            offsetPx = 36,
            filterMode = ReadingFilterMode.NONE,
            updatedAt = 1_700_000_000_000L,
        )
        val repo = RepeatJumpThreadRepository(savedPosition = savedPosition)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val vm = ThreadDetailViewModel(repo, gateway)
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "88100", title = "用户主动点击跳楼", fid = 2, targetPid = "3020", navSeq = 1L),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf("3020"), repo.loadByPidCalls)
        assertEquals(3, vm.uiState.value.page)
        assertTrue("navSeq > 0 的新跳转必须落位至 floor:3020", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" })
    }

    /** R-M7-P2 S2：w933dp 三栏真实 NextAppRoot 链路下验证同帖跳楼 → 返回 → 再点同一链接 → 再次跳楼。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun s2_threePaneNextAppRoot_w933dp_sameThreadLinkJumpBackAndRepeatJump() {
        val settingsRepo = RecordingSettingsRepository(AppSettings())
        val authRepo = OfflineAuthRepository()
        val smsUnread = MutableStateFlow(0)
        val notifyUnread = MutableStateFlow(0)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val threadListRepo = OfflineThreadListRepository()
        val searchRepo = OfflineSearchRepository()
        val detailRepo = RepeatJumpThreadRepository()
        val hostVm = NextAppHostViewModel(settingsRepo, detailRepo)
        val forumVm = ForumViewModel(threadListRepo, searchRepo, settingsRepo, authRepo, gateway)
        val createdDetailVms = mutableListOf<ThreadDetailViewModel>()

        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumVm as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(detailRepo, gateway).also(createdDetailVms::add) as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }
        val parentOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
        }

        hostVm.openThread(ThreadNavArgs(tid = "88100", title = "三栏同帖重跳测试", fid = 2))
        var hostBackDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = notifyUnread.asStateFlow(),
                        hostViewModel = hostVm,
                        forumViewModel = forumVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 前提：w933dp 三栏同时展示中栏帖子列表与右栏帖子 1 楼，第 18 楼尚未进入视口
        composeRule.onNodeWithText("测试帖子标题 1001").assertIsDisplayed()
        composeRule.onNodeWithText("跳转到同帖第18楼链接").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("第 1 页第 18 楼正文", substring = true).fetchSemanticsNodes().size)
        assertEquals(1, createdDetailVms.size)
        val detailVm = createdDetailVms.single()
        assertFalse(detailVm.uiState.value.canPopPositionStack)

        // 1. 首次点击正文原始链接 pid=1018 -> 经 NextAppRoot 真实链路跳至 18 楼并压 PositionStack
        val seq0 = hostVm.state.value.selectedThread!!.navSeq
        composeRule.onNodeWithText("跳转到同帖第18楼链接").performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        val seq1 = hostVm.state.value.selectedThread!!.navSeq
        assertTrue("首次点击 navSeq 必须递增", seq1 > seq0)
        assertEquals("1018", hostVm.state.value.selectedThread?.targetPid)
        composeRule.onNodeWithText("第 1 页第 18 楼正文", substring = true).assertIsDisplayed()
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        assertEquals("同帖跳楼不得重建 ThreadDetailViewModel", 1, createdDetailVms.size)

        // 2. 系统返回 -> PositionStack 弹回 1 楼且右栏不关闭
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("跳转到同帖第18楼链接").assertIsDisplayed()
        assertEquals("88100", hostVm.state.value.selectedThread?.tid)
        assertFalse(detailVm.uiState.value.canPopPositionStack)

        // 3. 再次点击同一条链接 -> 经 MultiPaneReadingContainer 槽位透传新 navSeq，再次跳至 18 楼
        composeRule.onNodeWithText("跳转到同帖第18楼链接").performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        val seq2 = hostVm.state.value.selectedThread!!.navSeq
        assertTrue("再次点击同一链接 navSeq 必须继续递增", seq2 > seq1)
        composeRule.onNodeWithText("第 1 页第 18 楼正文", substring = true).assertIsDisplayed()
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        assertEquals("再次跳楼仍复用同一 ThreadDetailViewModel", 1, createdDetailVms.size)
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for condition")
            }
            Thread.sleep(10L)
        }
    }

    private class RepeatJumpThreadRepository(
        private val savedPosition: ReadingPositionEntity? = null,
    ) : ThreadDetailRepository {
        val loadByPidCalls = mutableListOf<String>()
        var loadLastPageCalls: Int = 0

        private val page1Floors: List<PostFloor> = (1..20).map { idx ->
            val pid = (1000 + idx).toString()
            val blocks = if (idx == 1) {
                listOf(
                    ContentBlock.Text(
                        listOf(
                            InlineSpan.Link(
                                label = "跳转到同帖第18楼链接",
                                url = "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=88100&pid=1018",
                            ),
                        ),
                    ),
                )
            } else {
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 1 页第 $idx 楼正文\n补充行 1\n补充行 2\n补充行 3"))))
            }
            PostFloor(pid, idx, 1, "u$idx", "用户$idx", null, "2026-11-27 10:0$idx", false, null, null, null, blocks)
        }

        private val page2Floors: List<PostFloor> = (1..20).map { idx ->
            val pid = (2000 + idx).toString()
            PostFloor(
                pid, 20 + idx, 2, "u2_$idx", "二页用户$idx", null, "2026-11-27 11:0$idx", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 2 页第 ${20 + idx} 楼正文\n内容行 1\n内容行 2\n内容行 3")))),
            )
        }

        private val page3Floors: List<PostFloor> = (1..20).map { idx ->
            val pid = (3000 + idx).toString()
            PostFloor(
                pid, 40 + idx, 3, "u3_$idx", "末页用户$idx", null, "2026-11-27 12:0$idx", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 3 页第 ${40 + idx} 楼正文\n内容行 1\n内容行 2")))),
            )
        }

        private fun meta() = DiscuzPageMeta(DiscuzSessionInfo(true, "100", "Tester"), "fh", null, PromptCounts())

        private fun pageResult(pageNum: Int, floors: List<PostFloor>, targetPid: String? = null) = ThreadDetailLoadResult.Success(
            page = ThreadDetailPage("88100", 2, "同帖重复跳楼测试", pageNum, 3, floors, 20, meta(), true),
            filter = ThreadDetailFilter.All,
            threadAuthorId = "u1",
            forumName = "Discovery",
            targetPostId = targetPid,
        )

        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            when (page) {
                1 -> pageResult(1, page1Floors)
                2 -> pageResult(2, page2Floors)
                3 -> pageResult(3, page3Floors)
                else -> ThreadDetailError.InvalidPage("unsupported page $page")
            }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            loadLastPageCalls += 1
            return pageResult(3, page3Floors, targetPid = page3Floors.last().postId)
        }

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            loadByPidCalls += pid
            return when {
                pid.startsWith("3") -> pageResult(3, page3Floors, targetPid = pid)
                pid.startsWith("2") -> pageResult(2, page2Floors, targetPid = pid)
                else -> pageResult(1, page1Floors, targetPid = pid)
            }
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? =
            savedPosition?.takeIf { it.tid == tid }
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            ThreadDetailError.InvalidInput("unused")
    }
}
