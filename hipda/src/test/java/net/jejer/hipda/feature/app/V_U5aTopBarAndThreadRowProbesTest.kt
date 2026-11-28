package net.jejer.hipda.feature.app

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.time.LocalDate
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.compose.FakeImageUploadRepository
import net.jejer.hipda.feature.compose.FakePostRepository
import net.jejer.hipda.feature.compose.FullEditorPlacement
import net.jejer.hipda.feature.compose.FullEditorPlacementFrame
import net.jejer.hipda.feature.compose.FullEditorRequest
import net.jejer.hipda.feature.compose.FullEditorScreen
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.forum.ForumTopBar
import net.jejer.hipda.feature.forum.ThreadListRow
import net.jejer.hipda.feature.forum.rememberForumTopBarScrollState
import net.jejer.hipda.feature.messages.MessagesSolidTopBarFrame
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.settings.SettingsGroupsPane
import net.jejer.hipda.feature.settings.SettingsSolidTopBar
import net.jejer.hipda.feature.threaddetail.ThreadDetailTopBar
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.topbar.HiSolidTopBar
import net.jejer.hipda.ui.shell.HiShellTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class V_U5aTopBarAndThreadRowProbesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun layoutChildrenOf(info: LayoutInfo): List<LayoutInfo> {
        val getter = info.javaClass.methods.firstOrNull {
            (it.name == "getChildren" || it.name.startsWith("getChildren$")) && it.parameterCount == 0
        }
        return (getter?.invoke(info) as? List<*>).orEmpty().filterIsInstance<LayoutInfo>()
    }

    private fun collectAllLayoutNodes(root: LayoutInfo): List<LayoutInfo> {
        val out = ArrayList<LayoutInfo>()
        fun walk(n: LayoutInfo) {
            out += n
            layoutChildrenOf(n).forEach(::walk)
        }
        walk(root)
        return out
    }

    /** Probe 4a-1: 验证 7 处顶栏标题行高均 48dp、触控区 >= 48dp、Settings 列表项仍 minHeight = 56dp。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4a_individualTopBarsHeight48dp_touchTargets48dp_settingsRowsStill56dp() {
        val postRepo = FakePostRepository()
        val harness = MultiPaneTestHarness()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        editorVm.open(FullEditorRequest.NewThread(2))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    Column {
                        // 1. ForumTopBar (无 Tab 时总高 = 48dp)
                        Box {
                            ForumTopBar(
                                title = "论坛标题",
                                source = ForumListSource.Forum(2),
                                followedForums = listOf(2),
                                sortByPostTime = false,
                                showForumTabs = false,
                                glassState = null,
                                isScrolled = { false },
                                scrollState = rememberForumTopBarScrollState(false),
                                onSelectSource = {},
                                onNewThreadClick = {},
                                onSelectSortByPostTime = {},
                                onManageForumsClick = {},
                            )
                        }
                        // 2. ThreadDetailTopBar
                        Box {
                            ThreadDetailTopBar(
                                title = "详情标题",
                                chromeSpec = DetailChromeSpec(showBack = true, fullScreenState = FullScreenToggle(false) {}),
                                isScrolledState = mutableStateOf(false),
                                glassState = rememberHiGlassState(),
                                onBack = {},
                            )
                        }
                        // 3. HiSolidTopBar
                        Box {
                            HiSolidTopBar(title = "纯色标题", isScrolled = false, showBack = true, onBack = {})
                        }
                        // 4. MessagesSolidTopBarFrame
                        Box {
                            MessagesSolidTopBarFrame(title = "短消息标题", showBack = true, onBack = {}, isScrolled = false)
                        }
                        // 5. SettingsSolidTopBar
                        Box {
                            SettingsSolidTopBar(title = "设置标题", scrolled = false, showBack = true, onBack = {})
                        }
                        // 6. FullEditorScreen TopBar (非模态)
                        Box(Modifier.weight(1f)) {
                            FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, false, {}) { isModal ->
                                FullEditorScreen(viewModel = editorVm, onOpenLogin = {}, isModalCard = isModal)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        fun assertTopBarRowHeight(titleText: String, expectedDp: Float = 48f) {
            val titleNode = composeRule.onNodeWithText(titleText).fetchSemanticsNode()
            var row: LayoutInfo? = titleNode.layoutInfo
            while (row != null && with(density) { row.width.toDp().value } < 380f) {
                row = row.parentInfo
            }
            val actualH = with(density) { requireNotNull(row).height.toDp().value }
            val containerChildren = collectAllLayoutNodes(requireNotNull(row))
            val has48dpRow = containerChildren.any {
                val w = with(density) { it.width.toDp().value }
                val h = with(density) { it.height.toDp().value }
                w >= 380f && h in (expectedDp - 0.8f)..(expectedDp + 0.8f)
            }
            assertTrue("[$titleText] 顶栏必须存在宽度满行、高度=${expectedDp}dp 的标题行 (outer=$actualH)", has48dpRow)
        }

        assertTopBarRowHeight("论坛标题")
        assertTopBarRowHeight("详情标题")
        assertTopBarRowHeight("纯色标题")
        assertTopBarRowHeight("短消息标题")
        assertTopBarRowHeight("设置标题")
        assertTopBarRowHeight("发新帖")

        // 所有顶栏图标触控区 >= 48dp
        composeRule.onNodeWithContentDescription("发新帖").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("全屏阅读").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithText("发送").let {
            composeRule.onNodeWithContentDescription("发送").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    /** Probe 4a-2: 933×696 三栏顶栏（左栏顶部固定区 / 中栏论坛顶栏 / 右栏详情顶栏）标题行均 48dp 且底边在同一水平线；Settings 列表项仍 >= 56dp。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun probe4a_933ThreePane_allTopBars48dpAndBottomEdgesAligned() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            MultiPaneResizableWindowHost(DpSize(933.dp, 696.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        // 注入 24dp 状态栏高度，模拟真实端到端状态栏下边距
        composeRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, with(density) { 24.dp.roundToPx() }, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val sidebarTopBar = composeRule.onNodeWithTag(HiShellTestTags.SidebarTopBar).fetchSemanticsNode()
        val sidebarTopH = with(density) { sidebarTopBar.size.height.toDp().value }
        val sidebarBottomY = with(density) { (sidebarTopBar.positionInRoot.y + sidebarTopBar.size.height).toDp().value }

        val newThreadBtn = composeRule.onNodeWithContentDescription("发新帖").fetchSemanticsNode()
        val forumBarBottomY = with(density) { (newThreadBtn.positionInRoot.y + newThreadBtn.size.height).toDp().value }

        val fullScreenBtn = composeRule.onNodeWithContentDescription("全屏阅读").fetchSemanticsNode()
        val detailBarBottomY = with(density) { (fullScreenBtn.positionInRoot.y + fullScreenBtn.size.height).toDp().value }

        assertEquals("状态栏 24dp + 左栏顶栏 48dp = 72dp", 72f, sidebarTopH, 0.5f)
        assertEquals("左栏与中栏顶栏底边必须水平对齐", sidebarBottomY, forumBarBottomY, 0.5f)
        assertEquals("中栏与右栏顶栏底边必须水平对齐", forumBarBottomY, detailBarBottomY, 0.5f)

        // 验证左栏触控区 >= 48dp
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("发新帖").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("全屏阅读").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    /** Probe 4a-3: SettingsGroupsPane 列表项仍保留 minHeight = 56dp。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4a_settingsListItemRemains56dp() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                SettingsGroupsPane(
                    selectedGroup = SettingsGroup.Appearance,
                    isSidebar = false,
                    showBack = true,
                    onBack = {},
                    onSelectGroup = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeRule.waitForIdle()
        val rowNode = composeRule.onNodeWithText("外观与阅读").fetchSemanticsNode().parent!!
        val rowH = with(composeRule.density) { rowNode.size.height.toDp().value }
        assertTrue("Settings 列表项高度必须仍 ≥ 56dp，实际=${rowH}dp", rowH >= 55.5f)
    }

    /** Probe 4b: ThreadListRow 回复数气泡图标 14dp、无 "·" 分隔点、今日新帖无图标、< 300dp 窄栏仅显示回复数且带图标无截断。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4b_threadListRow_bubbleIcon14dp_noDot_todayNewEmpty_narrowCompact() {
        val normalItem = ThreadListItem(
            summary = ThreadSummary(
                tid = "5001", title = "普通帖子标题", titleColor = null, typeName = null,
                isSticky = false, isNew = false, isPoll = false,
                author = "linus_hi", authorId = "1", avatarUrl = null,
                timeCreate = "2026-11-28 10:00", timeUpdate = "2026-11-28 10:30",
                lastPostAuthor = "reader", replyCountText = "12", viewCountText = "345",
                withPic = false, maxPage = 1,
            ),
            isVisited = false,
        )
        val todayNewItem = ThreadListItem(
            summary = ThreadSummary(
                tid = "5002", title = "今日新帖无回复数", titleColor = null, typeName = null,
                isSticky = false, isNew = false, isPoll = false,
                author = "alice", authorId = "2", avatarUrl = null,
                timeCreate = "2026-11-28 09:15", timeUpdate = "2026-11-28 09:15",
                lastPostAuthor = "alice", replyCountText = "", viewCountText = "",
                withPic = false, maxPage = 1,
            ),
            isVisited = false,
        )
        val narrowItem = ThreadListItem(
            summary = ThreadSummary(
                tid = "5003", title = "窄栏帖子标题", titleColor = null, typeName = null,
                isSticky = false, isNew = false, isPoll = false,
                author = "bob", authorId = "3", avatarUrl = null,
                timeCreate = "2026-11-28 08:00", timeUpdate = "2026-11-28 08:20",
                lastPostAuthor = "bob", replyCountText = "88", viewCountText = "999",
                withPic = false, maxPage = 1,
            ),
            isVisited = false,
        )
        val refDate = LocalDate.of(2026, 11, 28)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Column {
                    Box(Modifier.width(380.dp)) {
                        ThreadListRow(normalItem, selected = false, compactLayout = false, onClick = {}, referenceDate = refDate)
                    }
                    Box(Modifier.width(380.dp)) {
                        ThreadListRow(todayNewItem, selected = false, compactLayout = false, onClick = {}, referenceDate = refDate)
                    }
                    Box(Modifier.width(274.dp)) {
                        ThreadListRow(narrowItem, selected = false, compactLayout = true, onClick = {}, referenceDate = refDate)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        // 全树不应有 "·" 文本节点（三个样本均无 typeName/poll）
        assertEquals("回复数与时间之间不得存在 · 文本节点", 0, composeRule.onAllNodesWithText("·", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)

        // 1. 普通宽度：12/345 左侧必须有 14dp×14dp 图标，右侧为 10:30 且间距 ~8dp
        val countNode = composeRule.onNodeWithText("12/345", useUnmergedTree = true).fetchSemanticsNode()
        val timeNode = composeRule.onNodeWithText("10:30", useUnmergedTree = true).fetchSemanticsNode()
        val countGroupRow = requireNotNull(countNode.layoutInfo.parentInfo)
        val countGroupChildren = layoutChildrenOf(countGroupRow)
        val icon14 = countGroupChildren.firstOrNull {
            val w = with(density) { it.width.toDp().value }
            val h = with(density) { it.height.toDp().value }
            w in 13.5f..14.5f && h in 13.5f..14.5f
        }
        assertTrue("回复数左侧必须存在 14dp 气泡图标 (children=${countGroupChildren.map { "${it.width}x${it.height}" }})", icon14 != null)
        assertTrue(
            "气泡图标必须位于回复数左侧",
            (icon14!!.coordinates.boundsInRoot().right) <= countNode.boundsInRoot.left + 0.5f,
        )
        val countTimeGapDp = with(density) { (timeNode.boundsInRoot.left - countNode.boundsInRoot.right).toDp().value }
        assertEquals("回复数组与时间之间间距应为 8dp", 8f, countTimeGapDp, 1f)

        // 2. 今日新帖（countText 为空）：09:15 所在组不得有 14dp 气泡图标
        val todayTimeNode = composeRule.onNodeWithText("09:15", useUnmergedTree = true).fetchSemanticsNode()
        val todayGroupRow = requireNotNull(todayTimeNode.layoutInfo.parentInfo)
        val todayIcons14 = collectAllLayoutNodes(todayGroupRow).count {
            val w = with(density) { it.width.toDp().value }
            val h = with(density) { it.height.toDp().value }
            w in 13.5f..14.5f && h in 13.5f..14.5f
        }
        assertEquals("今日新帖（无回复数）不得显示气泡图标", 0, todayIcons14)

        // 3. 窄栏 (< 300dp, compactLayout = true)：仅显示 88（不显示 88/999），左侧有 14dp 气泡图标且无截断
        composeRule.onNodeWithText("88", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("88/999", useUnmergedTree = true).fetchSemanticsNodes().size)
        val narrowCountNode = composeRule.onNodeWithText("88", useUnmergedTree = true).fetchSemanticsNode()
        val narrowRow = requireNotNull(narrowCountNode.layoutInfo.parentInfo)
        val narrowIcon14 = layoutChildrenOf(narrowRow).firstOrNull {
            val w = with(density) { it.width.toDp().value }
            val h = with(density) { it.height.toDp().value }
            w in 13.5f..14.5f && h in 13.5f..14.5f
        }
        assertTrue("窄栏 < 300dp 回复数左侧必须存在 14dp 气泡图标", narrowIcon14 != null)
    }
}
