package net.jejer.hipda.feature.app

import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dagger.BindsOptionalOf
import dagger.Module
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import net.jejer.hipda.feature.common.HiThemeSettingsAdapter
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NextAppTracerBulletTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 闭合 R-BATCH5 B4-E0：在 411dp 将列表与详情均滚至非首项，
     * 断言 411dp → 749dp → 411dp 切换前后 Repo 请求增量 == 0、
     * 同一个 ThreadDetailViewModel 实例未重建、列表与详情 LazyListState 索引与像素偏移完全不变。
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

        // 在 411dp 列表滚至非首项
        val forumListState = harness.hostViewModel.forumListStateFor(2)
        composeRule.runOnIdle {
            forumListState.requestScrollToItem(index = 6, scrollOffset = 42)
        }
        composeRule.waitForIdle()
        val forumIndexBefore = forumListState.firstVisibleItemIndex
        val forumOffsetBefore = forumListState.firstVisibleItemScrollOffset
        assertTrue("列表应滚至非首项，实际 index=$forumIndexBefore", forumIndexBefore > 0)

        // 点击当前可见的帖子进入单栏详情
        val targetTid = "1007"
        composeRule.onNodeWithText("测试帖子标题 $targetTid").performClick()
        composeRule.waitForIdle()

        assertEquals("首次进帖应发出 1 次详情请求", 1, harness.threadDetailRepo.loadPageCalls.get())
        assertTrue(harness.hostViewModel.hasThreadStore(targetTid))
        val detailOwnerBefore = harness.hostViewModel.viewModelStoreOwnerForThread(
            tid = targetTid,
            defaultViewModelProviderFactory = harness.detailVmFactory,
        )
        val detailVmBefore = ViewModelProvider(detailOwnerBefore)["thread:$targetTid", ThreadDetailViewModel::class.java]

        // 在 411dp 详情滚至非首项
        val detailListState = harness.hostViewModel.threadListStateFor(targetTid)
        composeRule.runOnIdle {
            detailListState.requestScrollToItem(index = 8, scrollOffset = 56)
        }
        composeRule.waitForIdle()
        val detailIndexBefore = detailListState.firstVisibleItemIndex
        val detailOffsetBefore = detailListState.firstVisibleItemScrollOffset
        assertTrue("详情应滚至非首项，实际 index=$detailIndexBefore", detailIndexBefore > 0)

        val listCallsSnapshot = harness.threadListRepo.refreshCalls.get()
        val detailCallsSnapshot = harness.threadDetailRepo.loadPageCalls.get()

        // 411dp → 749dp（折叠屏展开进入紧凑三栏）
        windowSizeState.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertIsDisplayed()
        assertEquals("411→749dp 列表请求增量必须为 0", listCallsSnapshot, harness.threadListRepo.refreshCalls.get())
        assertEquals("411→749dp 详情请求增量必须为 0", detailCallsSnapshot, harness.threadDetailRepo.loadPageCalls.get())
        val detailVmAt749 = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread(targetTid, harness.detailVmFactory),
        )["thread:$targetTid", ThreadDetailViewModel::class.java]
        assertSame("411→749dp 必须是同一个 ThreadDetailViewModel 实例", detailVmBefore, detailVmAt749)
        assertEquals("411→749dp 列表首项索引不变", forumIndexBefore, forumListState.firstVisibleItemIndex)
        assertEquals("411→749dp 列表像素偏移不变", forumOffsetBefore, forumListState.firstVisibleItemScrollOffset)
        assertEquals("411→749dp 详情首项索引不变", detailIndexBefore, detailListState.firstVisibleItemIndex)
        assertEquals("411→749dp 详情像素偏移不变", detailOffsetBefore, detailListState.firstVisibleItemScrollOffset)

        // 749dp → 411dp（合上回单栏）
        windowSizeState.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        assertEquals("749→411dp 列表请求增量必须为 0", listCallsSnapshot, harness.threadListRepo.refreshCalls.get())
        assertEquals("749→411dp 详情请求增量必须为 0", detailCallsSnapshot, harness.threadDetailRepo.loadPageCalls.get())
        val detailVmBack411 = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread(targetTid, harness.detailVmFactory),
        )["thread:$targetTid", ThreadDetailViewModel::class.java]
        assertSame("749→411dp 必须是同一个 ThreadDetailViewModel 实例", detailVmBefore, detailVmBack411)
        assertEquals("749→411dp 详情首项索引不变", detailIndexBefore, detailListState.firstVisibleItemIndex)
        assertEquals("749→411dp 详情像素偏移不变", detailOffsetBefore, detailListState.firstVisibleItemScrollOffset)
    }

    /**
     * 负责人补充点 2：单栏进帖后 ForumPane 离开组合树，返回列表后首项索引和偏移不变、请求增量为 0。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun singlePaneEnterThreadAndBack_preservesForumScrollOffsetWithZeroExtraRequests() {
        val harness = TestHarness()
        composeRule.setContent {
            harness.RenderRoot()
        }
        composeRule.waitForIdle()

        val forumListState = harness.hostViewModel.forumListStateFor(2)
        composeRule.runOnIdle {
            forumListState.requestScrollToItem(index = 9, scrollOffset = 48)
        }
        composeRule.waitForIdle()

        val indexBefore = forumListState.firstVisibleItemIndex
        val offsetBefore = forumListState.firstVisibleItemScrollOffset
        val callsBefore = harness.threadListRepo.refreshCalls.get()
        assertTrue("列表应处于非首项位置，实际 index=$indexBefore", indexBefore > 0)

        // 点帖后 ForumPane 离开组合树
        composeRule.onNodeWithText("测试帖子标题 1010").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertTrue(harness.hostViewModel.hasThreadStore("1010"))

        // 返回列表
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()

        assertFalse("返回列表后已关闭帖子的 Store 应释放", harness.hostViewModel.hasThreadStore("1010"))
        assertEquals("返回列表后请求增量必须为 0", callsBefore, harness.threadListRepo.refreshCalls.get())
        assertEquals("返回列表后首项索引不变", indexBefore, forumListState.firstVisibleItemIndex)
        assertEquals("返回列表后像素偏移不变", offsetBefore, forumListState.firstVisibleItemScrollOffset)
    }

    /**
     * 三栏切换新 tid 与清空右栏时，旧 tid 的 ViewModelStore 立即调用 clear()。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun threePaneSwitchTidAndClearDetail_clearsOldThreadViewModelStore() {
        val harness = TestHarness()
        composeRule.setContent {
            harness.RenderRoot()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        var cleared1001 = false
        val vm1001 = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread("1001", harness.detailVmFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        vm1001.addCloseable(Closeable { cleared1001 = true })

        // 切换到 1002，验证 1001 的 Store 立即 clear()
        composeRule.onNodeWithText("测试帖子标题 1002").performClick()
        composeRule.waitForIdle()

        assertTrue("切换新 tid 后旧 tid=1001 的 ViewModelStore 必须立即 clear()", cleared1001)
        assertFalse(harness.hostViewModel.hasThreadStore("1001"))
        assertTrue(harness.hostViewModel.hasThreadStore("1002"))

        var cleared1002 = false
        val vm1002 = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread("1002", harness.detailVmFactory),
        )["thread:1002", ThreadDetailViewModel::class.java]
        vm1002.addCloseable(Closeable { cleared1002 = true })

        // 清空右栏
        composeRule.runOnIdle { harness.hostViewModel.closeThread() }
        composeRule.waitForIdle()

        assertTrue("清空右栏后 tid=1002 的 ViewModelStore 必须立即 clear()", cleared1002)
        assertFalse(harness.hostViewModel.hasThreadStore("1002"))
        composeRule.onNodeWithText("选一篇帖子开始阅读").assertIsDisplayed()
    }

    /**
     * 负责人补充点 1：按 tid 托管的 ViewModelStoreOwner 实现 HasDefaultViewModelProviderFactory，
     * 映射表放在 NextAppHostViewModel 内，旋转/配置变化（Activity 重建）后仍是同一个 ThreadDetailViewModel 实例。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun activityRotation_retainsHostStoreMapAndSameThreadDetailViewModelInstance() {
        val detailRepo = FakeThreadDetailRepository()
        val gateway = createIdleGateway()
        RotationHarnessActivity.detailFactoryProvider = {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ThreadDetailViewModel(detailRepo, gateway) as T
            }
        }

        val controller = Robolectric.buildActivity(RotationHarnessActivity::class.java).setup()
        try {
            val activityBefore = controller.get()
            val hostBefore = ViewModelProvider(activityBefore)[NextAppHostViewModel::class.java]
            hostBefore.openThread(ThreadNavArgs(tid = "2048", title = "旋转保活帖", fid = 2))

            val factoryBefore = RotationHarnessActivity.detailFactoryProvider()
            val ownerBefore = hostBefore.viewModelStoreOwnerForThread(
                tid = "2048",
                defaultViewModelProviderFactory = factoryBefore,
                defaultViewModelCreationExtras = activityBefore.defaultViewModelCreationExtras,
            )
            assertTrue(
                "按 tid 托管的 owner 必须实现 HasDefaultViewModelProviderFactory",
                ownerBefore is HasDefaultViewModelProviderFactory,
            )
            assertSame(factoryBefore, ownerBefore.defaultViewModelProviderFactory)

            val vmBefore = ViewModelProvider(ownerBefore)["thread:2048", ThreadDetailViewModel::class.java]
            vmBefore.bindArgs(ThreadNavArgs(tid = "2048", title = "旋转保活帖", fid = 2))
            val callsBefore = detailRepo.loadPageCalls.get()
            assertEquals(1, callsBefore)

            // 触发真实 Android 配置变化（竖屏 → 横屏，Activity 销毁并重建）
            val rotatedConfig = Configuration(activityBefore.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 749
                screenHeightDp = 559
            }
            controller.configurationChange(rotatedConfig)

            val activityAfter = controller.get()
            assertNotSame("旋转后 Activity 实例应已重建", activityBefore, activityAfter)

            val hostAfter = ViewModelProvider(activityAfter)[NextAppHostViewModel::class.java]
            assertSame("旋转后 NextAppHostViewModel 应存活且为同一实例", hostBefore, hostAfter)
            assertEquals("2048", hostAfter.state.value.selectedThread?.tid)
            assertTrue("旋转后 tid Store 映射不能丢失", hostAfter.hasThreadStore("2048"))

            val factoryAfter = RotationHarnessActivity.detailFactoryProvider()
            val ownerAfter = hostAfter.viewModelStoreOwnerForThread(
                tid = "2048",
                defaultViewModelProviderFactory = factoryAfter,
                defaultViewModelCreationExtras = activityAfter.defaultViewModelCreationExtras,
            )
            assertSame("旋转后应复用同一个 tid ViewModelStore", ownerBefore.viewModelStore, ownerAfter.viewModelStore)
            assertSame("旋转后 owner 应绑定新工厂避免泄漏旧 Activity", factoryAfter, ownerAfter.defaultViewModelProviderFactory)

            val vmAfter = ViewModelProvider(ownerAfter)["thread:2048", ThreadDetailViewModel::class.java]
            assertSame("旋转/配置变化后必须是同一个 ThreadDetailViewModel 实例", vmBefore, vmAfter)

            vmAfter.bindArgs(ThreadNavArgs(tid = "2048", title = "旋转保活帖", fid = 2))
            assertEquals("旋转后不应重拉 loadPage", callsBefore, detailRepo.loadPageCalls.get())
        } finally {
            controller.destroy()
        }
    }

    /**
     * 实时切换深浅色、纯黑与字号时不重建、不丢失阅读位置、不重拉网络。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun liveThemeAndFontSwitch_keepsReadingPositionAndZeroReloads() {
        val harness = TestHarness()
        var observedDark = false
        var observedPureBlack = false
        var observedFontStep = HiFontSizeStep.DEFAULT

        composeRule.setContent {
            harness.RenderRoot(
                themeObserver = {
                    observedDark = HiTheme.state.darkTheme
                    observedPureBlack = HiTheme.state.pureBlack
                    observedFontStep = HiTheme.typography.fontSizeStep
                },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val detailState = harness.hostViewModel.threadListStateFor("1001")
        composeRule.runOnIdle { detailState.requestScrollToItem(index = 7, scrollOffset = 34) }
        composeRule.waitForIdle()

        val vmBefore = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread("1001", harness.detailVmFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        val listCallsBefore = harness.threadListRepo.refreshCalls.get()
        val detailCallsBefore = harness.threadDetailRepo.loadPageCalls.get()

        harness.settingsRepo.updateSettings(
            AppSettings(
                themeMode = ThemeMode.DARK,
                pureBlack = true,
                fontStep = 4,
            ),
        )
        composeRule.waitForIdle()

        assertTrue("深色模式应实时生效", observedDark)
        assertTrue("纯黑模式应实时生效", observedPureBlack)
        assertEquals("第 5 档字号应实时生效", HiFontSizeStep.STEP_5, observedFontStep)
        assertEquals("切主题不应丢失详情首项索引", 7, detailState.firstVisibleItemIndex)
        assertEquals("切主题不应丢失详情像素偏移", 34, detailState.firstVisibleItemScrollOffset)
        assertEquals("切主题列表请求增量为 0", listCallsBefore, harness.threadListRepo.refreshCalls.get())
        assertEquals("切主题详情请求增量为 0", detailCallsBefore, harness.threadDetailRepo.loadPageCalls.get())
        val vmAfter = ViewModelProvider(
            harness.hostViewModel.viewModelStoreOwnerForThread("1001", harness.detailVmFactory),
        )["thread:1001", ThreadDetailViewModel::class.java]
        assertSame("切主题后必须是同一个 ThreadDetailViewModel", vmBefore, vmAfter)
    }

    /**
     * 单栏 `managesGlassSource = { it == ShellDestination.Forum }` 严格兄弟采样拓扑验证 +
     * `LoginOverlayRenderer` 插槽与无阻塞 Coil 3 DI 绑定验证。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun singlePaneGlassTopology_loginSlotAndImageLoaderModule_areWiredProperly() {
        val harness = TestHarness()
        val overlayRendered = mutableStateOf(false)
        val renderer = object : LoginOverlayRenderer {
            @Composable
            override fun Content(
                mode: LoginOverlayMode,
                onDismiss: () -> Unit,
                onLoginSuccess: () -> Unit,
            ) {
                overlayRendered.value = true
                Text("登录插槽:${mode.name}")
            }
        }

        composeRule.setContent {
            harness.RenderRoot(loginOverlayRenderer = renderer)
        }
        composeRule.waitForIdle()

        // 1. 验证外层 ContentContainer 未挂载 LayerBackdropElement，仅 ForumPane 内部 LazyColumn 挂载
        val containerNode = composeRule.onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode()
        val containerHasBackdrop = containerNode.layoutInfo.getModifierInfo().any {
            it.modifier.toString().contains("LayerBackdrop", ignoreCase = true)
        }
        assertFalse("外层 ContentContainer 不得挂载 LayerBackdropElement", containerHasBackdrop)

        val scrollNodes = composeRule.onAllNodes(
            SemanticsMatcher("hasVerticalScrollAxisRange") {
                SemanticsProperties.VerticalScrollAxisRange in it.config
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(
            "ForumPane 的 LazyColumn 必须挂载 shellGlassSource()（LayerBackdrop）",
            scrollNodes.any { node ->
                node.layoutInfo.getModifierInfo().any {
                    it.modifier.toString().contains("LayerBackdrop", ignoreCase = true)
                }
            },
        )
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

        // 2. 验证 LoginOverlayRenderer 插槽绑定与触发
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        assertTrue(overlayRendered.value)
        composeRule.onNodeWithText("登录插槽:Login").assertIsDisplayed()

        // 3. 验证 AppShellBindingModule @BindsOptionalOf 与 ImageLoaderModule 极薄非阻塞构建
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

    private class TestHarness {
        val settingsRepo = FakeSettingsRepository()
        val authRepo = FakeAuthRepository()
        val smsUnread = MutableStateFlow(2)
        val notifyUnread = MutableStateFlow(5)
        val smsUnreadFlow = smsUnread.asStateFlow()
        val notifyUnreadFlow = notifyUnread.asStateFlow()
        val gateway = createIdleGateway()
        val threadListRepo = FakeThreadListRepository()
        val threadDetailRepo = FakeThreadDetailRepository()
        val hostViewModel = NextAppHostViewModel()
        val forumViewModel = ForumViewModel(threadListRepo, settingsRepo, authRepo, gateway)
        val detailVmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ThreadDetailViewModel(threadDetailRepo, gateway) as T
        }

        @Composable
        fun RenderRoot(
            loginOverlayRenderer: LoginOverlayRenderer? = null,
            themeObserver: (@Composable () -> Unit)? = null,
        ) {
            NextAppRoot(
                settingsFlow = settingsRepo.settings,
                currentAccountFlow = authRepo.currentAccount,
                smsUnreadCountFlow = smsUnreadFlow,
                notifyUnreadCountFlow = notifyUnreadFlow,
                loginOverlayRenderer = loginOverlayRenderer,
                hostViewModel = hostViewModel,
                forumViewModel = forumViewModel,
                threadViewModelFactory = detailVmFactory,
            )
            if (themeObserver != null) {
                val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle()
                val cfg = HiThemeSettingsAdapter.toThemeConfig(settings)
                HiTheme(cfg.themeMode, cfg.pureBlack, cfg.seed, cfg.fontSizeStep, cfg.lineHeightPreset) {
                    themeObserver()
                }
            }
        }
    }

    @Composable
    private fun ResizableWindowHost(
        windowSize: DpSize,
        content: @Composable () -> Unit,
    ) {
        val density = LocalDensity.current
        val base = LocalWindowInfo.current
        val fakeWindowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = base.isWindowFocused
            override val containerDpSize: DpSize get() = windowSize
            override val containerSize: IntSize
                get() = with(density) {
                    IntSize(windowSize.width.toPx().roundToInt(), windowSize.height.toPx().roundToInt())
                }
        }
        CompositionLocalProvider(LocalWindowInfo provides fakeWindowInfo) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }

    private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
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

    private class FakeAuthRepository : AuthRepository {
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
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
            SilentReloginResult.NotLoggedIn
    }

    private class FakeThreadListRepository : ThreadListRepository {
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
            val summaries = (1..30).map { idx ->
                val tid = (1000 + idx).toString()
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
            return ThreadListBatchResult(
                query = query,
                resolvedSortByPostTime = query.sortByPostTime == true,
                rawThreads = summaries,
                items = items,
                fetchedPage = 1,
                lastPage = 3,
                hasMore = true,
                pagesFetchedInBatch = 1,
                meta = emptyPageMeta(),
            )
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

    private class FakeThreadDetailRepository : ThreadDetailRepository {
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
                    blocks = listOf(
                        ContentBlock.Text(
                            listOf(InlineSpan.Plain("帖子 $tid 第 $idx 楼正文内容，用于验证滚动位置与实例保活。")),
                        ),
                    ),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "测试帖子标题 $tid",
                    page = page,
                    lastPage = 3,
                    floors = floors,
                    postsPerPageEstimate = 30,
                    meta = emptyPageMeta(),
                    hasPostList = true,
                ),
                filter = filter,
                forumName = forumName,
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null

        override suspend fun saveReadingPosition(
            tid: String,
            page: Int,
            pid: String,
            offsetPx: Int,
            filterMode: ReadingFilterMode,
            filterUid: String?,
            filterUserName: String?,
        ) = Unit

        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }

        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            observeFloors(floorsFlow)

        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }

        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors

        override suspend fun submitPollVote(
            fid: Int,
            tid: String,
            formhash: String,
            optionIds: List<String>,
            maxChoices: Int?,
            priority: AccessPriority,
        ): PollVoteResult = throw UnsupportedOperationException("not used in E0")
    }

    private companion object {
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("RotationHarness") }
    }

    companion object {
        lateinit var detailFactoryProvider: () -> ViewModelProvider.Factory
    }
}
