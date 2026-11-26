package net.jejer.hipda.feature.app

import android.app.Application
import android.os.Build
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelHost
import net.jejer.hipda.feature.compose.QuickReplySheet
import net.jejer.hipda.feature.compose.QuickReplyTarget
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.settings.SettingsSolidTopBar
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.feature.threaddetail.chrome.ThreadBottomToolbar
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.challenge.ChallengeActivityBinding
import net.jejer.hipda.ui.challenge.ChallengeInteractionReason
import net.jejer.hipda.ui.challenge.ChallengeInteractionSession
import net.jejer.hipda.ui.challenge.ChallengeWebViewEvent
import net.jejer.hipda.ui.challenge.ChallengeWebViewHandle
import net.jejer.hipda.ui.challenge.ComposeChallengeInteractionPresenter
import net.jejer.hipda.ui.challenge.MainFrameState
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.applyHiEdgeToEdge
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellDestination
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.w3c.dom.Element

/**
 * M7-3 验收 1 & 4：覆盖 targetSdk=36 清单与大屏合规、API 28/33/35/36 Edge-to-Edge 系统栏对比度实时同步、
 * 顶部挖孔/三键横屏/IME 零双倍留白 Insets 验证，以及 API 35/36 预测性返回与全层 BackHandler 优先级栈兼容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class TargetSdk36ManifestEdgeToEdgeAndBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun grantDynamicReceiverPermissionForLegacyRobolectric() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test
    @Config(sdk = [36])
    fun manifestAndSdk36_complyWithPermissionsBackCallbackAndLargeScreenRules() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("targetSdkVersion 必须为 36", 36, app.applicationInfo.targetSdkVersion)
        assertEquals("applicationId 必须保持 net.jejer.hipda.next", "net.jejer.hipda.next", app.packageName)

        val moduleDir = resolveHipdaModuleDir().canonicalFile
        val tomlText = File(moduleDir.parentFile, "gradle/libs.versions.toml").readText()
        assertTrue(
            "libs.versions.toml 必须声明 targetSdk = \"36\"",
            Regex("""targetSdk\s*=\s*"36"""").containsMatchIn(tomlText),
        )

        val manifestDoc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(moduleDir, "src/main/AndroidManifest.xml"))

        // 1. 权限声明：含 POST_NOTIFICATIONS，WRITE_EXTERNAL_STORAGE 带 maxSdkVersion=28，严禁 MANAGE_EXTERNAL_STORAGE
        val permissions = manifestDoc.getElementsByTagName("uses-permission")
        val permMap = LinkedHashMap<String, String>()
        for (i in 0 until permissions.length) {
            val el = permissions.item(i) as Element
            permMap[el.getAttributeNS(ANDROID_NS, "name")] = el.getAttributeNS(ANDROID_NS, "maxSdkVersion")
        }
        assertTrue("清单必须声明 POST_NOTIFICATIONS", "android.permission.POST_NOTIFICATIONS" in permMap)
        assertEquals("WRITE_EXTERNAL_STORAGE 必须限定 maxSdkVersion=28", "28", permMap["android.permission.WRITE_EXTERNAL_STORAGE"])
        assertFalse("严禁声明 MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE" in permMap)

        // 2. <application> 启用预测性返回且保留备份排除规则
        val appEl = manifestDoc.getElementsByTagName("application").item(0) as Element
        assertEquals("true", appEl.getAttributeNS(ANDROID_NS, "enableOnBackInvokedCallback"))
        assertEquals("@xml/data_extraction_rules", appEl.getAttributeNS(ANDROID_NS, "dataExtractionRules"))
        assertEquals("@xml/backup_config", appEl.getAttributeNS(ANDROID_NS, "fullBackupContent"))

        // 3. 移除过时 android.max_aspect
        val metaData = manifestDoc.getElementsByTagName("meta-data")
        for (i in 0 until metaData.length) {
            val name = (metaData.item(i) as Element).getAttributeNS(ANDROID_NS, "name")
            assertFalse("清单不得保留过时 android.max_aspect", name == "android.max_aspect")
        }

        // 4. 全部 Activity 均无方向锁定或禁用可调整大小（符合 Android 16 大屏强制自适应）
        val activities = manifestDoc.getElementsByTagName("activity")
        assertTrue(activities.length > 0)
        for (i in 0 until activities.length) {
            val act = activities.item(i) as Element
            val actName = act.getAttributeNS(ANDROID_NS, "name")
            assertEquals("Activity $actName 不得锁定 screenOrientation", "", act.getAttributeNS(ANDROID_NS, "screenOrientation"))
            assertFalse("Activity $actName 不得设置 resizeableActivity=false", act.getAttributeNS(ANDROID_NS, "resizeableActivity") == "false")
        }

        // 5. provider_paths.xml 包含 external-cache-path
        val providerPaths = File(moduleDir, "src/main/res/xml/provider_paths.xml").readText()
        assertTrue("provider_paths.xml 必须包含 external-cache-path", providerPaths.contains("external-cache-path"))
    }

    @Test
    @Config(sdk = [28, 33, 35, 36], qualifiers = "w411dp-h891dp-xhdpi")
    fun edgeToEdgeSystemBars_syncsLightDarkPureBlackImageViewerAndChallengeAcrossApis() {
        val harness = MultiPaneTestHarness()
        val settingsFlow = MutableStateFlow(AppSettings(themeMode = ThemeMode.LIGHT, pureBlack = false))

        val imageUrl = "https://www.4d4y.com/forum/attachments/e2e_sync.jpg"
        val imageDetailRepo = SingleImageDetailRepo(imageUrl)
        val imageVmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                ThreadDetailViewModel::class.java -> ThreadDetailViewModel(imageDetailRepo, harness.gateway) as T
                QuickReplyViewModel::class.java -> QuickReplyViewModel(StubPostRepository, harness.authRepo, System::currentTimeMillis) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val imageParentOwner = object : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
            override val defaultViewModelProviderFactory = imageVmFactory
        }

        var closeViewerCallback: (() -> Unit)? = null
        var hostActivity: ComponentActivity? = null
        composeRule.setContent {
            hostActivity = LocalActivity.current as ComponentActivity
            CompositionLocalProvider(LocalViewModelStoreOwner provides imageParentOwner) {
                NextAppRoot(
                    settingsFlow = settingsFlow,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    imageLoader = createTestImageLoader(),
                    imageViewerRenderer = object : ImageViewerRenderer {
                        @Composable
                        override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                            closeViewerCallback = onDismiss
                            Surface(Modifier.fillMaxSize()) { Text("看图系统栏测试") }
                        }
                    },
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        val activity = requireNotNull(hostActivity)
        fun insetsController() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        // 1. 浅色主题 -> 深色系统栏图标
        assertTrue("浅色主题状态栏图标应为深色", insetsController().isAppearanceLightStatusBars)
        assertTrue("浅色主题导航栏图标应为深色", insetsController().isAppearanceLightNavigationBars)

        // 2. 深色主题 -> 浅色系统栏图标
        settingsFlow.value = AppSettings(themeMode = ThemeMode.DARK, pureBlack = false)
        composeRule.waitForIdle()
        assertFalse("深色主题状态栏图标应为浅色", insetsController().isAppearanceLightStatusBars)
        assertFalse("深色主题导航栏图标应为浅色", insetsController().isAppearanceLightNavigationBars)

        // 3. 纯黑 OLED -> 浅色系统栏图标
        settingsFlow.value = AppSettings(themeMode = ThemeMode.DARK, pureBlack = true)
        composeRule.waitForIdle()
        assertFalse("纯黑主题状态栏图标应为浅色", insetsController().isAppearanceLightStatusBars)
        assertFalse("纯黑主题导航栏图标应为浅色", insetsController().isAppearanceLightNavigationBars)

        // 4. 切回浅色主题 -> 恢复深色图标
        settingsFlow.value = AppSettings(themeMode = ThemeMode.LIGHT, pureBlack = false)
        composeRule.waitForIdle()
        assertTrue(insetsController().isAppearanceLightStatusBars)
        assertTrue(insetsController().isAppearanceLightNavigationBars)

        // 5. 浅色主题下点击真实图片打开全屏看图 -> 系统栏立即翻为浅色图标；关闭看图 -> 立即恢复深色图标
        composeRule.runOnIdle { harness.hostViewModel.openThread(ThreadNavArgs("1001", "看图同步帖", 2)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("看图测试正文").assertIsDisplayed()
        val imageMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and hasClickAction()
        composeRule.onAllNodes(imageMatcher, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("看图系统栏测试").assertIsDisplayed()
        assertFalse("全屏暗色看图期间状态栏图标必须切为浅色", insetsController().isAppearanceLightStatusBars)
        assertFalse("全屏暗色看图期间导航栏图标必须切为浅色", insetsController().isAppearanceLightNavigationBars)

        composeRule.runOnIdle { requireNotNull(closeViewerCallback).invoke() }
        composeRule.waitForIdle()
        assertTrue("退出全屏看图后浅色主题状态栏图标必须恢复深色", insetsController().isAppearanceLightStatusBars)
        assertTrue("退出全屏看图后浅色主题导航栏图标必须恢复深色", insetsController().isAppearanceLightNavigationBars)

        // 6. ChallengeActivity + ComposeChallengeInteractionPresenter 实时跟随主题同步 Edge-to-Edge 系统栏
        val challengeSettings = MutableStateFlow(AppSettings(themeMode = ThemeMode.LIGHT, pureBlack = false))
        val challengeController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val challengeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val challengeActivity = challengeController.get()
            challengeActivity.applyHiEdgeToEdge(darkTheme = false)
            val root = FrameLayout(challengeActivity)
            challengeActivity.setContentView(root)
            val webView = WebView(challengeActivity)
            var cleared = false
            val presenter = ComposeChallengeInteractionPresenter(
                settingsFlow = challengeSettings,
                pollIntervalMs = 20L,
                dismissTimeoutMs = 200L,
            )
            val webViewHandle = StubChallengeWebViewHandle(challengeActivity, root, webView)
            val session = ChallengeInteractionSession(
                triggerUrl = "https://www.4d4y.com/forum/index.php".toHttpUrl(),
                reason = ChallengeInteractionReason.InteractiveBegin,
                webViewHandle = webViewHandle,
                activityBinding = webViewHandle.activityBinding,
                clearanceChecker = { cleared },
            )
            val presentJob = challengeScope.async { presenter.present(session) }
            val ctrl = WindowCompat.getInsetsController(challengeActivity.window, challengeActivity.window.decorView)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                root.childCount > 0 && ctrl.isAppearanceLightStatusBars
            }
            assertTrue("Challenge 浅色主题状态栏图标应为深色", ctrl.isAppearanceLightStatusBars)

            challengeSettings.value = AppSettings(themeMode = ThemeMode.DARK, pureBlack = true)
            Snapshot.sendApplyNotifications()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                !ctrl.isAppearanceLightStatusBars && !ctrl.isAppearanceLightNavigationBars
            }
            assertFalse("Challenge 深色/纯黑主题状态栏图标应切为浅色", ctrl.isAppearanceLightStatusBars)
            assertFalse("Challenge 深色/纯黑主题导航栏图标应切为浅色", ctrl.isAppearanceLightNavigationBars)

            cleared = true
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                presentJob.isCompleted
            }
            assertEquals(SolveResult.Cleared, runBlocking { presentJob.await() })
        } finally {
            challengeScope.cancel()
            challengeController.destroy()
        }
    }

    @Test
    @Config(sdk = [28, 33, 35, 36], qualifiers = "w411dp-h891dp-xhdpi")
    fun edgeToEdgeInsets_topCutoutAndThreadBottomToolbarWithIme_haveZeroOverlapAndZeroDoublePadding() {
        var hostView: View? = null
        var jumpExpanded by mutableStateOf(false)

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme {
                val glassState = rememberHiGlassState()
                Box(Modifier.fillMaxSize()) {
                    SettingsSolidTopBar(title = "全面屏挖孔标题", scrolled = false, showBack = false, onBack = null)
                    ThreadBottomToolbar(
                        page = 2,
                        lastPage = 10,
                        isFavorited = false,
                        isAttention = false,
                        glassState = glassState,
                        suppressBottomGlass = false,
                        isJumpBarExpanded = jumpExpanded,
                        onExpandedChange = { jumpExpanded = it },
                        onReplyClick = {},
                        onJumpToPage = {},
                        onToggleFavorite = {},
                        onRefreshClick = {},
                        onToggleAttention = {},
                        onCopyLinkClick = {},
                        onOpenInBrowserClick = {},
                        onShareClick = {},
                        bottomInset = 48.dp,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootHeightPx = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val statusPx = with(density) { 24.dp.roundToPx() }
        val cutoutTopPx = with(density) { 40.dp.roundToPx() }
        val navPx = with(density) { 48.dp.roundToPx() }
        val imePx = with(density) { 280.dp.roundToPx() }

        // 1. 注入 40dp 顶部挖孔（高于 24dp 状态栏）+ 48dp 三键导航栏 + 280dp IME
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                requireNotNull(hostView),
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutoutTopPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .build(),
            )
        }
        composeRule.waitForIdle()

        // 顶栏标题必须完整位于 40dp 挖孔下方（API 30+ 支持原生 displayCutout inset；API 28 校验 statusBars 24dp）
        val titleBounds = composeRule.onNodeWithText("全面屏挖孔标题").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val expectedMinTopPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cutoutTopPx else statusPx
        assertTrue("顶栏标题必须避开顶部安全区，实际 top=${with(density) { titleBounds.top.toDp() }}", titleBounds.top >= expectedMinTopPx - 1f)

        val replyBounds = composeRule.onNodeWithText("回复").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gapFromRootBottomDp = with(density) { (rootHeightPx - replyBounds.bottom).toDp() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 底部三段工具条必须仅避让 280dp IME + 8dp 间距（= 288dp），严禁叠加 48dp navBar 造成 336dp 双倍留白
            assertTrue(
                "ThreadBottomToolbar 在 48dp 三键导航 + 280dp IME 下不得双倍留白（期望 ~288–306dp，实际 $gapFromRootBottomDp）",
                gapFromRootBottomDp in 284.dp..310.dp,
            )
            // 展开内联跳页条（411dp 紧凑双行 104dp）：底行「关闭」按钮贴 280dp IME + 8dp 间距，无 48dp 双倍留白
            jumpExpanded = true
            composeRule.waitForIdle()
            val closeBtn = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val jumpBottomGapDp = with(density) { (rootHeightPx - closeBtn.bottom).toDp() }
            assertTrue(
                "InlinePageJumpBar 底行在 IME 下不得叠加 48dp 双倍留白（期望 ~288–308dp，实际 $jumpBottomGapDp）",
                jumpBottomGapDp in 284.dp..308.dp,
            )
        } else {
            // API 28 无独立 Type.ime，验证 48dp 三键导航栏 + 8dp 间距正确避让
            assertTrue(
                "API 28 ThreadBottomToolbar 应避让 48dp 导航栏 + 8dp 间距，实际 $gapFromRootBottomDp",
                gapFromRootBottomDp in 52.dp..76.dp,
            )
        }
    }

    @Test
    @Config(sdk = [28, 33, 35, 36], qualifiers = "w891dp-h411dp-xhdpi")
    fun edgeToEdgeInsets_landscapeThreeButtonNavBar_clearsHorizontalSafeAreaWithoutOverlap() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize()) { Text("横屏安全内容") }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val navRightPx = with(density) { 48.dp.roundToPx() }
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                requireNotNull(hostView),
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navRightPx, 0))
                    .build(),
            )
        }
        composeRule.waitForIdle()

        val contentBounds = composeRule.onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "横屏右侧 48dp 三键导航栏不得遮挡内容容器（right=${contentBounds.right}, rootRight=${rootBounds.right}, navRightPx=$navRightPx）",
            contentBounds.right <= rootBounds.right - navRightPx + 1.5f,
        )
    }

    @Test
    @Config(sdk = [35, 36], qualifiers = "w749dp-h559dp-xhdpi")
    fun predictiveBack_preservesFullBackHandlerPriorityStackAndYieldsAtRoot() {
        val imageUrl = "https://www.4d4y.com/forum/attachments/back_stack.jpg"
        val harness = MultiPaneTestHarness()
        val imageDetailRepo = SingleImageDetailRepo(imageUrl)
        val imageVmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                ThreadDetailViewModel::class.java -> ThreadDetailViewModel(imageDetailRepo, harness.gateway) as T
                QuickReplyViewModel::class.java -> QuickReplyViewModel(StubPostRepository, harness.authRepo, System::currentTimeMillis) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val imageParentOwner = object : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
            override val defaultViewModelProviderFactory = imageVmFactory
        }

        var backDispatcher: OnBackPressedDispatcher? = null
        var imageOverlayOpen by mutableStateOf(false)
        var loginOverlayOpen by mutableStateOf(false)

        val fakeLogin = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                loginOverlayOpen = true
                BackHandler(enabled = true) {
                    loginOverlayOpen = false
                    onDismiss()
                }
                Surface(Modifier.fillMaxSize()) { Text("登录浮层栈项") }
            }
        }
        val fakeImageViewer = object : ImageViewerRenderer {
            @Composable
            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                imageOverlayOpen = true
                BackHandler(enabled = true) {
                    imageOverlayOpen = false
                    onDismiss()
                }
                Surface(Modifier.fillMaxSize()) { Text("看图浮层栈项") }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides imageParentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    imageLoader = createTestImageLoader(),
                    loginOverlayRenderer = fakeLogin,
                    imageViewerRenderer = fakeImageViewer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        val dispatcher = requireNotNull(backDispatcher)

        // 0. 根页面（无已打开详情/浮层）-> OnBackPressedDispatcher 必须无启用回调，交还系统支持 Android 15/16 预测性返回桌面
        assertFalse("根页面必须释放全部 BackHandler 以支持系统预测性返回", dispatcher.hasEnabledCallbacks())

        // 1. 依次叠加：帖子详情 -> 快速回复 -> 64dp 图标轨模态抽屉 -> 登录浮层 -> 看图浮层（同时打开看图与登录！）
        composeRule.runOnIdle { harness.hostViewModel.openThread(ThreadNavArgs("1001", "返回栈全链路帖", 2)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 点击图片块打开看图，同时打开登录浮层（验证 看图 > 登录 > 抽屉 > 快速回复 > 多栏返回栈）
        val imageMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "正文"))
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录浮层栈项").assertIsDisplayed()

        // 退出登录后点真实正文图片打开看图，再叠加登录浮层 -> 确认看图优先级严格高于登录
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(loginOverlayOpen)
        // 关闭抽屉使正文图片可点，点击打开看图后立即再叠加抽屉与登录浮层
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        // 先关快速回复点击图片再验证 看图 > 登录 > 多栏返回栈
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onAllNodes(imageMatcher, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("看图浮层栈项").assertIsDisplayed()

        // 看图打开的同时触发登录浮层 -> 看图仍居栈顶（看图 > 登录），退出看图后登录浮层立即接管
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        assertTrue(imageOverlayOpen)
        assertEquals(LoginOverlayMode.Login, harness.hostViewModel.state.value.loginOverlayMode)

        // Back #1：先关闭看图，登录浮层仍在并立即呈现
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse("第 1 次返回必须先关闭看图", imageOverlayOpen)
        assertTrue("看图关闭后登录浮层应保留在栈上", loginOverlayOpen)
        composeRule.onNodeWithText("登录浮层栈项").assertIsDisplayed()

        // Back #2：关闭登录浮层，底层帖子仍在
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse("第 2 次返回必须关闭登录浮层", loginOverlayOpen)
        assertNotNull("登录关闭后底层帖子不得被误关", harness.hostViewModel.state.value.selectedThread)

        // Back #3：关闭多栏帖子详情 -> 回到根页面并完全释放 BackHandler
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.selectedThread)
        assertFalse("全部浮层与详情退出后根页面必须 0 启用 BackHandler", dispatcher.hasEnabledCallbacks())
    }

    private fun pumpMainLooper() {
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    private fun resolveHipdaModuleDir(): File {
        val candidates = listOf(File("."), File("hipda"))
        return candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Cannot locate hipda module dir from ${File(".").absolutePath}")
    }

    private class SingleImageDetailRepo(private val imageUrl: String) : ThreadDetailRepository {
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

    private fun createTestImageLoader(): ImageLoader = object : ImageLoader {
        override val defaults = ImageRequest.Defaults.DEFAULT
        override val components = ComponentRegistry()
        override val memoryCache: MemoryCache? = null
        override val diskCache: DiskCache? = null
        override fun enqueue(request: ImageRequest): Disposable {
            val result = SuccessResult(ColorImage(0xFF4488CC.toInt(), width = 120, height = 90), request, DataSource.MEMORY)
            request.target?.onSuccess(result.image)
            return object : Disposable {
                override val job: Deferred<ImageResult> = CompletableDeferred(result)
                override val isDisposed: Boolean = true
                override fun dispose() = Unit
            }
        }
        override suspend fun execute(request: ImageRequest): ImageResult =
            SuccessResult(ColorImage(0xFF4488CC.toInt(), width = 120, height = 90), request, DataSource.MEMORY)
        override fun newBuilder(): ImageLoader.Builder = error("unused")
        override fun shutdown() = Unit
    }

    private class StubChallengeWebViewHandle(
        activity: ComponentActivity,
        root: FrameLayout,
        webView: WebView,
    ) : ChallengeWebViewHandle {
        override val events: Flow<ChallengeWebViewEvent> = emptyFlow()
        override val activityBinding: ChallengeActivityBinding = object : ChallengeActivityBinding {
            override val activity: ComponentActivity = activity
            override val webView: WebView = webView
            override val rootContainer: FrameLayout = root
            override fun setInteractiveMode(interactive: Boolean) = Unit
        }
        override fun installChallengeMessageListener() = Unit
        override fun loadUrl(url: String) = Unit
        override fun reload() = Unit
        override fun mainFrameState(): MainFrameState = MainFrameState()
        override suspend fun isDomInChallengeState(): Boolean = false
        override fun close() = Unit
        override suspend fun closeAndAwait() = Unit
    }

    private object StubPostRepository : PostRepository {
        override val cooldownUntilMs: kotlinx.coroutines.flow.StateFlow<Long> = MutableStateFlow(0L)
        override fun getRemainingCooldownSeconds(): Int = 0
        override suspend fun fetchPrePost(request: PrePostRequest, priority: AccessPriority): PrePostResult =
            throw UnsupportedOperationException()
        override suspend fun submitPost(request: PostSubmitRequest, priority: AccessPriority): PostSubmitResult =
            throw UnsupportedOperationException()
        override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = flowOf(null)
        override suspend fun saveDraft(key: DraftKey, subject: String, typeId: String, content: String, quotePid: String?, attachments: List<String>) = Unit
        override suspend fun deleteDraft(key: DraftKey) = Unit
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
