package net.jejer.hipda.feature.app

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.ColorImage
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E-slots 验收测试：覆盖 4 个 M6 可选渲染器插槽（`MessagesPaneRenderer`、`SearchMinePaneRenderer`、
 * `SettingsProfilePaneRenderer`、`ImageViewerRenderer`）有绑定实现 + 未绑定 `null` 回退双分支，
 * 以及 `NextAppStateSaver` 新增状态往返恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FeaturePaneSlotsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w749dp-h891dp-xhdpi")
    fun messagesPaneRenderer_boundAndUnbound_handlesListConversationBackAndUserProfile() {
        val harness = MultiPaneTestHarness()
        val windowSize = mutableStateOf(DpSize(749.dp, 559.dp))
        var useBound by mutableStateOf(true)
        var backDispatcher: OnBackPressedDispatcher? = null

        val fakeMessages = object : MessagesPaneRenderer {
            @Composable
            override fun ListContent(
                entry: ShellSidebarEntry,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                selectedConversationUid: String?,
                onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
                onOpenConversation: (SmsConversationArgs) -> Unit,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenUserProfile: (uid: String, username: String) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
                listState: LazyListState,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("消息列表:${entry::class.simpleName}:sidebar=$isSidebar:sel=${selectedConversationUid ?: "none"}")
                    Button(onClick = { onSelectMessageEntry(ShellSidebarEntry.Notify) }) { Text("切至提醒") }
                    Button(onClick = { onOpenConversation(SmsConversationArgs("42", "alice")) }) { Text("打开对话42") }
                }
            }

            @Composable
            override fun ConversationContent(
                args: SmsConversationArgs,
                showBack: Boolean,
                onBack: () -> Unit,
                onOpenUserProfile: (uid: String, username: String) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("短消息对话:${args.uid}:${args.username}:back=$showBack")
                    Button(onClick = { onOpenUserProfile(args.uid, args.username) }) { Text("看对方资料") }
                    Button(onClick = onBack) { Text("退出对话") }
                }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(windowSize.value) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        messagesPaneRenderer = if (useBound) fakeMessages else null,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        // 1. 三栏有绑定：Sms -> Notify -> Sms -> 打开对话 -> 下钻资料 -> 连续返回
        composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.Sms) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息列表:Sms:sidebar=true:sel=none").assertIsDisplayed()

        composeRule.onNodeWithText("切至提醒").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息列表:Notify:sidebar=true:sel=none").assertIsDisplayed()

        composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.Sms) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开对话42").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息列表:Sms:sidebar=true:sel=42").assertIsDisplayed()
        composeRule.onNodeWithText("短消息对话:42:alice:back=false").assertIsDisplayed()

        composeRule.onNodeWithText("看对方资料").performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.hostViewModel.state.value.detailDrilldownStack.size)

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("短消息对话:42:alice:back=false").assertIsDisplayed()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.selectedSmsConversation)

        // 2. 单栏有绑定：打开对话退场底部胶囊、点返回退出对话恢复列表与胶囊
        windowSize.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息列表:Sms:sidebar=false:sel=none").assertIsDisplayed()
        composeRule.onNodeWithText("打开对话42").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("短消息对话:42:alice:back=true").assertIsDisplayed()
        assertTrue(harness.hostViewModel.state.value.isSinglePaneSecondary)

        composeRule.onNodeWithText("退出对话").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消息列表:Sms:sidebar=false:sel=none").assertIsDisplayed()

        // 3. 未绑定（null）回退干净占位且无崩溃
        useBound = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("暂无短消息").assertIsDisplayed()
        composeRule.runOnIdle { harness.hostViewModel.openSmsConversation(SmsConversationArgs("42", "alice")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun searchMinePaneRenderer_boundAndUnbound_handlesSearchForumJumpMineMenuAndSubPages() {
        val harness = MultiPaneTestHarness()
        var useBound by mutableStateOf(true)
        var loginOverlayOpened: LoginOverlayMode? = null
        var backDispatcher: OnBackPressedDispatcher? = null

        val fakeLogin = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                loginOverlayOpened = mode
                Column(Modifier.fillMaxSize()) {
                    Text("登录浮层:${mode.name}")
                    Button(onClick = onDismiss) { Text("关登录") }
                }
            }
        }
        val fakeSearchMine = object : SearchMinePaneRenderer {
            @Composable
            override fun SearchContent(
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenForum: (fid: Int) -> Unit,
                onOpenUserProfile: (uid: String, username: String) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
                listState: LazyListState,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("搜索页主体")
                    Button(onClick = { onOpenForum(6) }) { Text("去版块6") }
                }
            }

            @Composable
            override fun MineMenuContent(
                account: SavedAccount?,
                onAccountClick: () -> Unit,
                onSelectSubEntry: (ShellSidebarEntry) -> Unit,
                contentPadding: PaddingValues,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("我的菜单:${account?.username ?: "guest"}")
                    Button(onClick = onAccountClick) { Text("点账号入口") }
                    Button(onClick = { onSelectSubEntry(ShellSidebarEntry.FavoritesAndAttention) }) { Text("进收藏与关注") }
                }
            }

            @Composable
            override fun MineSubPageContent(
                entry: ShellSidebarEntry,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onSelectMineEntry: (ShellSidebarEntry) -> Unit,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
                listState: LazyListState,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("我的子页:${entry::class.simpleName}:sidebar=$isSidebar:back=$showBack")
                    Button(onClick = { onOpenThread(ThreadNavArgs("1003", "收藏帖", 2)) }) { Text("开收藏帖1003") }
                    if (onBack != null) Button(onClick = onBack) { Text("回我的菜单") }
                }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    loginOverlayRenderer = fakeLogin,
                    searchMinePaneRenderer = if (useBound) fakeSearchMine else null,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        // 1. 搜索页渲染 + onOpenForum 跳转版块 6
        composeRule.onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("搜索页主体").assertIsDisplayed()
        composeRule.onNodeWithText("去版块6").performClick()
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Forum, harness.hostViewModel.state.value.selectedDestination)
        assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)

        // 2. 我的菜单 -> 账号入口 -> 我的子页 -> 开帖 -> 返回子页 -> 返回菜单
        composeRule.onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Mine)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("我的菜单:HiUser").assertIsDisplayed()

        composeRule.onNodeWithText("点账号入口").performClick()
        composeRule.waitForIdle()
        assertEquals(LoginOverlayMode.AccountMenu, loginOverlayOpened)
        composeRule.onNodeWithText("关登录").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("进收藏与关注").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("我的子页:FavoritesAndAttention:sidebar=false:back=true").assertIsDisplayed()

        composeRule.onNodeWithText("开收藏帖1003").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1003").assertIsDisplayed()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("我的子页:FavoritesAndAttention:sidebar=false:back=true").assertIsDisplayed()

        composeRule.onNodeWithText("回我的菜单").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("我的菜单:HiUser").assertIsDisplayed()

        // 3. 未绑定（null）回退干净占位且无崩溃
        useBound = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("HiUser").assertIsDisplayed()
        composeRule.onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("搜索标题、正文或作者").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w749dp-h891dp-xhdpi")
    fun settingsProfilePaneRenderer_boundAndUnbound_handlesSettingsGroupsDetailAndProfileSmsDrilldown() {
        val harness = MultiPaneTestHarness()
        val windowSize = mutableStateOf(DpSize(749.dp, 559.dp))
        var useBound by mutableStateOf(true)
        var backDispatcher: OnBackPressedDispatcher? = null

        val fakeMessages = object : MessagesPaneRenderer {
            @Composable
            override fun ListContent(
                entry: ShellSidebarEntry, isSidebar: Boolean, showBack: Boolean, onBack: (() -> Unit)?,
                selectedConversationUid: String?, onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
                onOpenConversation: (SmsConversationArgs) -> Unit, onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenUserProfile: (String, String) -> Unit, onOpenLogin: () -> Unit,
                contentPadding: PaddingValues, listState: LazyListState,
            ) = Unit

            @Composable
            override fun ConversationContent(
                args: SmsConversationArgs, showBack: Boolean, onBack: () -> Unit,
                onOpenUserProfile: (String, String) -> Unit, onOpenLogin: () -> Unit, contentPadding: PaddingValues,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("资料直达对话:${args.uid}:${args.username}:back=$showBack")
                    Button(onClick = onBack) { Text("返回资料") }
                }
            }
        }
        val fakeSettingsProfile = object : SettingsProfilePaneRenderer {
            @Composable
            override fun SettingsGroupsContent(
                selectedGroup: SettingsGroup,
                isSidebar: Boolean,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onSelectGroup: (SettingsGroup) -> Unit,
                contentPadding: PaddingValues,
                listState: LazyListState,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("设置分组:${selectedGroup.name}:sidebar=$isSidebar:back=$showBack")
                    Button(onClick = { onSelectGroup(SettingsGroup.NetworkAndCache) }) { Text("选网络与缓存") }
                }
            }

            @Composable
            override fun SettingsDetailContent(
                group: SettingsGroup,
                showBack: Boolean,
                onBack: (() -> Unit)?,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("设置详情:${group.name}:back=$showBack")
                    if (onBack != null) Button(onClick = onBack) { Text("返回设置组") }
                }
            }

            @Composable
            override fun UserProfileContent(
                uid: String,
                username: String,
                showBack: Boolean,
                onBack: () -> Unit,
                onOpenThread: (ThreadNavArgs) -> Unit,
                onOpenSmsConversation: (SmsConversationArgs) -> Unit,
                onOpenLogin: () -> Unit,
                contentPadding: PaddingValues,
            ) {
                Column(Modifier.fillMaxSize().padding(contentPadding)) {
                    Text("用户资料:$uid:$username:back=$showBack")
                    Button(onClick = { onOpenSmsConversation(SmsConversationArgs(uid, username)) }) { Text("发短消息给TA") }
                    Button(onClick = onBack) { Text("关闭资料") }
                }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(windowSize.value) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        messagesPaneRenderer = if (useBound) fakeMessages else null,
                        settingsProfilePaneRenderer = if (useBound) fakeSettingsProfile else null,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        // 1. 三栏：设置分组 + 右栏详情联动 + 用户资料下钻 + 发短消息下钻 + 逐级返回
        composeRule.runOnIdle { harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.Settings) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置分组:Appearance:sidebar=true:back=false").assertIsDisplayed()
        composeRule.onNodeWithText("设置详情:Appearance:back=false").assertIsDisplayed()

        composeRule.onNodeWithText("选网络与缓存").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置详情:NetworkAndCache:back=false").assertIsDisplayed()

        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("77", "bob") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("用户资料:77:bob:back=true").assertIsDisplayed()

        composeRule.onNodeWithText("发短消息给TA").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("资料直达对话:77:bob:back=true").assertIsDisplayed()

        composeRule.onNodeWithText("返回资料").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("用户资料:77:bob:back=true").assertIsDisplayed()

        composeRule.onNodeWithText("关闭资料").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置详情:NetworkAndCache:back=false").assertIsDisplayed()

        // 2. 单栏：5 组列表 -> 选组进详情 -> 返回分组列表
        windowSize.value = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置分组:NetworkAndCache:sidebar=false:back=true").assertIsDisplayed()
        composeRule.onNodeWithText("选网络与缓存").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置详情:NetworkAndCache:back=true").assertIsDisplayed()

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置分组:NetworkAndCache:sidebar=false:back=true").assertIsDisplayed()

        // 3. 未绑定（null）回退干净占位且无崩溃
        useBound = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("77", "bob") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("bob").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun imageViewerRenderer_realThreadDetailImageClickOpensOverlayWithSnackbarVisible_andNullIsSafeNoOp() {
        val imageUrl = "https://www.4d4y.com/forum/attachments/slot_demo.jpg"
        val harness = MultiPaneTestHarness()
        val imageDetailRepo = SingleImageThreadDetailRepository(imageUrl)
        val imageVmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                ThreadDetailViewModel::class.java -> ThreadDetailViewModel(imageDetailRepo, harness.gateway) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val imageParentOwner = object : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
            override val defaultViewModelProviderFactory = imageVmFactory
        }
        val fakeImageLoader = createSolidTestImageLoader()

        var useBound by mutableStateOf(true)
        var capturedArgs: ImageViewerLaunchArgs? = null
        val fakeImageViewer = object : ImageViewerRenderer {
            @Composable
            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                capturedArgs = args
                val snackbar = LocalPaneSnackbarController.current
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("全屏看图:${args.urls.firstOrNull().orEmpty()}")
                        Button(onClick = { scope.launch { snackbar.show("已保存到相册") } }) { Text("保存测试图") }
                        Button(onClick = onDismiss) { Text("关闭看图") }
                    }
                }
            }
        }

        harness.hostViewModel.openThread(ThreadNavArgs("1001", "含图测试帖", 2))
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides imageParentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    imageLoader = fakeImageLoader,
                    imageViewerRenderer = if (useBound) fakeImageViewer else null,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 有绑定：点击真实 ThreadDetailPane 内图片块触发 Overlay、浮层上方可见 Snackbar、关闭后退出
        val imageMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文"))
        composeRule.onAllNodes(imageMatcher, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(imageUrl), capturedArgs?.urls)
        composeRule.onNodeWithText("全屏看图:$imageUrl").assertIsDisplayed()

        composeRule.onNodeWithText("保存测试图").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已保存到相册").assertIsDisplayed()

        composeRule.onNodeWithText("关闭看图").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("全屏看图:$imageUrl").fetchSemanticsNodes().isEmpty())

        // 2. 未绑定（null）：点击同一真实图片块安全无操作、不崩溃
        capturedArgs = null
        useBound = false
        composeRule.waitForIdle()
        composeRule.onAllNodes(imageMatcher, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()
        assertNull(capturedArgs)
        assertTrue(composeRule.onAllNodesWithText("全屏看图:$imageUrl").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun nextAppStateSaver_roundTripsSmsConversationSettingsGroupAndDrilldownStack() {
        val scope = SaverScope { true }
        val original = NextAppState(
            selectedDestination = ShellDestination.Messages,
            selectedSidebarEntry = ShellSidebarEntry.Sms,
            selectedSmsConversation = SmsConversationArgs("42", "alice"),
            selectedSettingsGroup = SettingsGroup.NetworkAndCache,
            isSettingsDetailOpen = true,
            detailDrilldownStack = listOf(
                DetailDrilldownEntry.UserProfile("42", "alice"),
                DetailDrilldownEntry.SmsConversation(SmsConversationArgs("77", "bob")),
            ),
        )
        val saved = with(NextAppStateSaver) { scope.save(original) }
        val restored = NextAppStateSaver.restore(requireNotNull(saved))
        assertEquals(original, restored)
    }
}

private class SingleImageThreadDetailRepository(private val imageUrl: String) : ThreadDetailRepository {
    override suspend fun loadPage(
        tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val floor = PostFloor(
            postId = "${tid}_1", floor = 1, page = page, uid = "1", author = "贴图楼主", avatarUrl = null,
            timePost = "2026-11-26 07:00", isWarned = false, postStatus = null, signature = null, poll = null,
            blocks = listOf(
                ContentBlock.Text(listOf(InlineSpan.Plain("看图测试正文"))),
                ContentBlock.Image(fullUrl = imageUrl, thumbUrl = null, fileSizeBytes = 1024L),
            ),
        )
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(tid, 2, "含图测试帖", 1, 1, listOf(floor), 1, offlinePageMeta(), true),
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

private fun createSolidTestImageLoader(): ImageLoader = object : ImageLoader {
    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null
    override fun enqueue(request: ImageRequest): Disposable {
        val d = CompletableDeferred<ImageResult>()
        return object : Disposable {
            override val job: Deferred<ImageResult> = d
            override val isDisposed: Boolean get() = d.isCancelled
            override fun dispose() { d.cancel() }
        }
    }
    override suspend fun execute(request: ImageRequest): ImageResult =
        SuccessResult(ColorImage(0xFF336699.toInt(), 320, 200), request, DataSource.MEMORY)
    override fun shutdown() = Unit
    override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException()
}
