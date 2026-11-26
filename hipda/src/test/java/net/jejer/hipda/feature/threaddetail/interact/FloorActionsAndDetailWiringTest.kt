package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.favorite.FavoriteListResult
import net.jejer.hipda.data.favorite.FavoriteMutationResult
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteSyncResult
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
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
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.ImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.LocalPostLinkDispatcher
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.routing.DefaultPostLinkDispatcher
import net.jejer.hipda.feature.routing.ExternalLinkOpener
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
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertWcagContrastAtLeast
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 覆盖 M7-G3 全部验收与负责人补充要求（逐项撤回即红）：
 * 1. #5 楼层长按「文字选择」：局部 `SelectionContainer` 可自由拖选并唤起系统文字工具栏、默认不全局包裹、`BackHandler` 优先于 `PositionStack`；
 * 2. #6 楼层长按「分享」：1 楼 `viewthread.php?tid=`、非 1 楼 `redirect.php?goto=findpost&ptid=&pid=`、优先 `https`、标准术语无违禁词 `"回帖"`、`SafeLinkPolicy` 危险 scheme/`rawUserInfo` 反例 0 `startActivity`；
 * 3. #7 被警告楼层标识与弹窗：`isWarned` 对比度/48dp 热区/无障碍语义、`MockWebServer`（含 GBK 解码与 500 失败重试）拉取解析 `viewwarning` HTML、危险 URL 0 请求、`BackHandler` 优先关闭；
 * 4. G1 详情顶栏滚顶接线：点击标题空白滚回 `(0, 0)`、减动效 0ms 瞬移、不误触返回/全屏/只看楼主圆钮、0 新增玻璃标记；
 * 5. R-E16 S2 + R-M7-G1 S2：同帖内点击指向同 `tid` 站内链接与多栏同帖长按「直达末页」响应新 `targetPid`/`openLastPage`，经 `PrependAnchorController` 落位并先压 `PositionStack`；
 * 6. #9 M7-G2 图集 Provider 端到端接线：`ThreadDetailPane` 点第 2 楼第 3 张图打开看图显示正确索引并支持左右横滑查看同帖全部图片。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FloorActionsAndDetailWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(null)
    }

    @After
    fun tearDown() {
        ImageAspectRatioCache.Default.clear()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun textSelectionDialog_wrapsFloorPlainTextInRealSelectionContainer_andBackClosesBeforePositionStack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)!!
        clipboard.setPrimaryClip(ClipData.newPlainText("init", ""))
        val repo = MultiFloorTestRepository()
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var hostBackDispatcher: OnBackPressedDispatcher? = null
        val recordingToolbar = RecordingTextToolbar()

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalTextToolbar provides recordingToolbar) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "文字选择测试", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 先从 1 楼跳至第 5 楼压入 PositionStack，验证文字选择面板的 BackHandler 优先级高于 PositionStack
        composeRule.runOnIdle { vm.jumpToFloor(targetPid = "9988", targetTid = "2602293") }
        composeRule.waitForIdle()
        assertTrue("前提：PositionStack 应非空", vm.uiState.value.canPopPositionStack)

        // 默认态长按楼层呼出菜单（而非 SelectionContainer 文字选择工具栏），证明非选择态不全局包裹 SelectionContainer
        composeRule.onNodeWithText("第 5 楼可选正文片段 AlphaBeta", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals("默认非选择态不得唤起 TextToolbar", 0, recordingToolbar.showMenuCalls)
        composeRule.onNodeWithText("文字选择").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()

        // 文字选择面板已展示对应楼层标题与纯文本，并实际包裹在 SelectionContainer 中：
        // 拖选子串 + Ctrl+C 必须经 SelectionContainer 写入剪贴板（撤回 SelectionContainer 立即变红）
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "第 5 楼 · 坛友乙"))
        val selectableText = composeRule.onNode(hasText("第 5 楼可选正文片段 AlphaBeta", substring = true) and dialogPane)
            .assertIsDisplayed()
        selectableText.performMouseInput {
            moveTo(Offset(2f, centerY))
            press()
            moveTo(Offset(width - 2f, centerY))
            release()
        }
        composeRule.waitForIdle()
        selectableText.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
        }
        composeRule.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(
            "撤回 SelectionContainer 必红：在文字选择区拖选并复制必须得到楼层纯文本，实际：'$copied'",
            copied.contains("第 5 楼可选正文片段") || copied.contains("AlphaBeta"),
        )

        // 系统返回优先关闭文字选择面板，不得误弹出 PositionStack
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("关闭").fetchSemanticsNodes().size)
        assertTrue("Back 关闭文字选择后 PositionStack 仍应保留", vm.uiState.value.canPopPositionStack)

        // 再次打开并点击「关闭」按钮（≥ 48dp）正常退出
        composeRule.onNodeWithText("第 5 楼可选正文片段 AlphaBeta", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("文字选择").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("关闭").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("关闭").fetchSemanticsNodes().size)
    }

    @Test
    fun floorShare_floor1AndNonFloor1UrlsAndIntent_andDangerousSchemesBlockedWithZeroStarts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = MultiFloorTestRepository(threadTitle = "单楼分享主题")
        val settingsRepo = StaticSettingsRepository(AppSettings(forumServer = "https://www.4d4y.com"))
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(null, AuthState.SignedOut), NoOpFavoriteRepository, settingsRepo)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "2602293", title = "单楼分享主题", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // (a) 1 楼长按点击「分享」-> ACTION_CHOOSER 包裹 ACTION_SEND，含 viewthread.php?tid=2602293、「主题」与纯文本摘要
        composeRule.onNodeWithText("1 楼主题纯文本内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("分享").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()

        val floor1Chooser = shadowOf(app).nextStartedActivity
        assertNotNull("1 楼点击分享必须发起系统 Chooser Intent", floor1Chooser)
        assertEquals(Intent.ACTION_CHOOSER, floor1Chooser.action)
        @Suppress("DEPRECATION")
        val floor1Send = floor1Chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(floor1Send)
        assertEquals(Intent.ACTION_SEND, floor1Send!!.action)
        assertEquals("text/plain", floor1Send.type)
        val floor1Body = floor1Send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue("1 楼分享必须含规范 https viewthread 链接：$floor1Body", floor1Body.contains("https://www.4d4y.com/forum/viewthread.php?tid=2602293"))
        assertTrue("1 楼分享必须含标准词 主题：$floor1Body", floor1Body.contains("主题：单楼分享主题"))
        assertTrue("1 楼分享必须含 第 1 楼 · 楼主甲：$floor1Body", floor1Body.contains("第 1 楼 · 楼主甲"))
        assertTrue("1 楼分享必须含纯文本摘要：$floor1Body", floor1Body.contains("1 楼主题纯文本内容"))
        assertFalse("严禁含违禁词 回帖：$floor1Body", floor1Body.contains("回帖"))

        // (b) 非 1 楼（第 5 楼 pid=9988）点击「分享」-> 含 redirect.php?goto=findpost&ptid=2602293&pid=9988 与「回复」
        composeRule.onNodeWithText("第 5 楼可选正文片段 AlphaBeta", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()

        val floor5Chooser = shadowOf(app).nextStartedActivity
        assertNotNull("非 1 楼点击分享必须发起系统 Chooser Intent", floor5Chooser)
        @Suppress("DEPRECATION")
        val floor5Send = floor5Chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, floor5Send.action)
        val floor5Body = floor5Send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(
            "非 1 楼分享必须含规范 https pid 链接：$floor5Body",
            floor5Body.contains("https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988"),
        )
        assertTrue("非 1 楼分享必须含标准词 回复：$floor5Body", floor5Body.contains("回复：单楼分享主题"))
        assertTrue("非 1 楼分享必须含 第 5 楼 · 坛友乙：$floor5Body", floor5Body.contains("第 5 楼 · 坛友乙"))
        assertTrue("非 1 楼分享必须含纯文本摘要：$floor5Body", floor5Body.contains("第 5 楼可选正文片段 AlphaBeta"))
        assertFalse("严禁含违禁词 回帖：$floor5Body", floor5Body.contains("回帖"))

        // http 默认端口规范化为 https
        assertEquals(
            "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988",
            buildSafeFloorShareUrl("http://www.4d4y.com", "2602293", 5, "9988"),
        )

        // (c) 危险 scheme / rawUserInfo / 非法 host / 非法 tid/pid 全部拦截且 0 次 startActivity
        val maliciousServers = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:void(0)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///etc/passwd",
            "content://media/external/images/1",
            "data:text/html,<script>alert(1)</script>",
            "mailto:evil@example.com",
            "tel:10086",
            "ftp://evil.example.com",
            "vbscript:msgbox(1)",
            "about:blank",
            "https://user@evil.com",
            "https://user:pass@evil.com",
            "https://www.4d4y.com@evil.com",
            "https://evil.com/path with space",
            "https://evil.com/line\nbreak",
            "https:///missing-host",
            "https://www.4d4y.com?injected=1",
            "",
            "   ",
        )
        val floor5 = repo.sampleFloor5
        val startedIntents = mutableListOf<Intent>()
        for (badServer in maliciousServers) {
            assertNull("危险 forumServer 必须被 SafeLinkPolicy 拦截: $badServer", buildSafeFloorShareUrl(badServer, "2602293", 5, "9988"))
            assertFalse(
                "危险 forumServer 分享必须返回 false: $badServer",
                shareFloorPlainText(
                    context = app,
                    forumServer = badServer,
                    tid = "2602293",
                    threadTitle = "测试",
                    floor = floor5,
                    topicPrefix = "主题",
                    replyPrefix = "回复",
                    floorHeader = "第 5 楼 · 坛友乙",
                    chooserTitle = "分享",
                    startIntent = { startedIntents += it },
                ),
            )
        }
        assertNull("非法 tid 必须拦截", buildSafeFloorShareUrl("https://www.4d4y.com", "2602293&x=1", 1, "1"))
        assertNull("非法 pid 必须拦截", buildSafeFloorShareUrl("https://www.4d4y.com", "2602293", 5, "9988&evil=1"))
        assertEquals("危险 URL 严禁触发任何 startActivity", 0, startedIntents.size)
    }

    @Test
    fun warnedFloorBadge_semanticsContrastAndMockWebServerWarningDialogWithRetryAndBackPriority() {
        // 1. 校验深/浅/OLED 全 20 套预设下警告角标（onErrorContainer on errorContainer）对比度 ≥ 4.5:1
        val modes = listOf(Triple("Light", false, false), Triple("Dark", true, false), Triple("OLED", true, true))
        for (preset in HiColorPresets.ALL) {
            for ((modeName, dark, pureBlack) in modes) {
                val cs = hiColorSchemeFromSeed(preset.color, darkTheme = dark, pureBlack = pureBlack)
                assertWcagContrastAtLeast(4.5f, cs.errorContainer, cs.onErrorContainer, "${preset.id}/$modeName/WarnedBadge")
            }
        }

        // 2. 启动本地 MockWebServer（GBK 响应 + 500 失败重试）
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        val server = MockWebServer()
        server.start()
        val okHttpClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "HiPDA-Test-UA" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        )
        try {
            val baseServerUrl = server.url("").toString().trimEnd('/')
            val settingsRepo = StaticSettingsRepository(AppSettings(forumServer = baseServerUrl))
            val gateway = SiteAccessGateway(okHttpClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val repo = MultiFloorTestRepository()
            val vm = ThreadDetailViewModel(repo, gateway, StaticAuthRepository(null, AuthState.SignedOut), NoOpFavoriteRepository, settingsRepo)
            var hostBackDispatcher: OnBackPressedDispatcher? = null

            composeRule.setContent {
                hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "警告楼层测试", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
            composeRule.waitForIdle()

            // (a) 未被警告的 1 楼无点击语义，也无“查看警告记录”无障碍描述
            val normalFloor1Node = composeRule.onNodeWithText("1楼").fetchSemanticsNode()
            assertFalse("isWarned == false 的楼号不得有点击语义", SemanticsActions.OnClick in normalFloor1Node.config)
            assertEquals(0, composeRule.onAllNodesWithContentDescription("1楼，查看警告记录").fetchSemanticsNodes().size)

            // 先从 1 楼跳至 3 楼压入 PositionStack 以验证警告弹窗 BackHandler 优先级高于 PositionStack
            composeRule.runOnIdle { vm.jumpToFloor("5003", "2602293") }
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.canPopPositionStack)

            // 被警告的 3 楼具有警示点击语义与 ≥ 48×48dp 热区
            val warnedBadge = composeRule.onNodeWithContentDescription("3楼，查看警告记录")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            assertTrue("isWarned == true 的楼号必须具备点击语义", SemanticsActions.OnClick in warnedBadge.fetchSemanticsNode().config)

            // (b) 模拟 GBK 编码 viewwarning HTML 响应，点按 3 楼警告角标 -> 精确展示标题、每条记录与 moreconf 说明
            val warningHtml = """
                <html><body>
                <h3 class="float_ctrl"><em>用户警告记录</em><span><a href="javascript:;" class="float_close">关闭</a></span></h3>
                <div class="floatwrap">
                  <table class="list">
                    <tbody>
                      <tr><td>版主甲</td><td>2026-11-20 10:00</td><td>人身攻击</td></tr>
                      <tr><td>版主乙</td><td>2026-11-24 18:30</td><td>恶意灌水</td></tr>
                    </tbody>
                  </table>
                </div>
                <div class="moreconf">已被累计警告 2 次，30 天内累计 3 次将自动禁言。</div>
                </body></html>
            """.trimIndent()
            server.enqueue(gbkHtmlResponse(warningHtml))

            warnedBadge.performClick()
            awaitWallCondition(5_000L, { "req=${server.requestCount}" }) {
                server.requestCount >= 1 &&
                    composeRule.onAllNodesWithText("用户警告记录").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()

            val req1 = server.takeRequest()
            assertEquals("/forum/misc.php?action=viewwarning&tid=2602293&uid=4003", req1.target)
            composeRule.onNodeWithText("用户警告记录").assertIsDisplayed()
            composeRule.onNodeWithText("版主甲 2026-11-20 10:00 人身攻击").assertIsDisplayed()
            composeRule.onNodeWithText("版主乙 2026-11-24 18:30 恶意灌水").assertIsDisplayed()
            composeRule.onNodeWithText("已被累计警告 2 次，30 天内累计 3 次将自动禁言。").assertIsDisplayed()

            // Back 优先关闭警告弹窗而非弹出 PositionStack
            composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            assertEquals(0, composeRule.onAllNodesWithText("用户警告记录").fetchSemanticsNodes().size)
            assertTrue("Back 关闭警告弹窗后 PositionStack 仍应保留", vm.uiState.value.canPopPositionStack)

            // (c) 网络/HTTP 错误时原地显示「加载失败」+「重试」，点击重试成功后展示警告记录
            server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())
            server.enqueue(gbkHtmlResponse(warningHtml))

            composeRule.onNodeWithContentDescription("3楼，查看警告记录").performClick()
            awaitWallCondition(5_000L, { "req=${server.requestCount}" }) {
                server.requestCount >= 2 &&
                    composeRule.onAllNodesWithText("加载失败").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("加载失败").assertIsDisplayed()
            composeRule.onNodeWithText("重试").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

            awaitWallCondition(5_000L, { "req=${server.requestCount}" }) {
                server.requestCount >= 3 &&
                    composeRule.onAllNodesWithText("用户警告记录").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("版主甲 2026-11-20 10:00 人身攻击").assertIsDisplayed()
        } finally {
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    fun warningUrlAndHtmlParser_blocksDangerousSchemesAndParsesXmlAndDoubleEntities() {
        val server = MockWebServer()
        server.start()
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        try {
            val dangerousServers = listOf(
                "javascript:alert(1)",
                "intent://evil/#Intent;end",
                "file:///etc/passwd",
                "content://settings/system",
                "data:text/html,hi",
                "mailto:user@example.com",
                "tel:10086",
                "ftp://example.com",
                "https://user@evil.com",
                "https://user:pass@evil.com",
                "https://www.4d4y.com?bad=1",
                "",
            )
            for (bad in dangerousServers) {
                assertNull("buildSafeWarningUrl 必须拦截: $bad", buildSafeWarningUrl(bad, "100", "200"))
                val result = runBlocking { fetchFloorWarning(gateway, bad, "100", "200") }
                assertEquals(FloorWarningLoadState.Failed, result)
            }
            assertNull(buildSafeWarningUrl("https://www.4d4y.com", "100&x=1", "200"))
            assertNull(buildSafeWarningUrl("https://www.4d4y.com", "100", "200&x=1"))
            assertEquals("危险 URL 不得发出任何网络请求", 0, server.requestCount)

            val xmlWrapped = """
                <?xml version="1.0" encoding="gbk"?>
                <root><![CDATA[
                    <h3 class="float_ctrl"><em>警告详情 &amp;#128512;</em><a class="float_close">关闭</a></h3>
                    <div class="floatwrap"><table class="list"><tbody><tr><td>管理</td><td>违规 &amp;#128512;</td></tr></tbody></table></div>
                    <div class="moreconf">累计 1 次</div>
                ]]></root>
            """.trimIndent()
            val parsed = parseWarningDialogHtml(xmlWrapped)
            assertNotNull(parsed)
            assertEquals("警告详情 😀", parsed!!.title)
            assertEquals(listOf("管理 违规 😀"), parsed.records)
            assertEquals("累计 1 次", parsed.summary)
            assertNull("无关空壳错误页应返回 null", parseWarningDialogHtml("<html><body><div class='alert_error'>无权操作</div></body></html>"))
        } finally {
            server.close()
        }
    }

    @Test
    fun detailTopBar_tapTitleScrollsToTopNormalAndReducedMotion_withoutTriggeringActionButtons() {
        val repo = MultiFloorTestRepository(floorCount = 24, threadTitle = "详情顶栏滚顶标题")
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState
        var backClicks = 0
        var fullScreenToggles = 0

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "2602293", title = "详情顶栏滚顶标题", fid = 2),
                        chromeSpec = DetailChromeSpec(
                            showBack = true,
                            fullScreenState = FullScreenToggle(isFullScreen = false) { fullScreenToggles++ },
                        ),
                        onBack = { backClicks++ },
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 玻璃拓扑检查：仍严格保持 2 个 HiGlassMarker（顶栏 1 + 底部工具条 1），0 新增玻璃
        assertEquals(
            2,
            composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size,
        )

        // 滚离顶部至 index = 12, offset = 40
        composeRule.runOnIdle { listState.requestScrollToItem(index = 12, scrollOffset = 40) }
        composeRule.waitForIdle()
        assertTrue("前提：列表必须已滚离顶部", listState.firstVisibleItemIndex > 0)

        // 点击「返回」「全屏阅读」圆钮均不误触顶栏滚顶
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backClicks)
        assertEquals(1, fullScreenToggles)
        assertEquals("点击返回/全屏圆钮不得误滚顶", 12, listState.firstVisibleItemIndex)

        // 点击「只看楼主」圆钮触发过滤而不是滚顶原列表，再退出恢复
        composeRule.onNodeWithContentDescription("只看楼主").performClick()
        composeRule.waitForIdle()
        assertTrue("只看楼主按钮应正常切入 AuthorOnly", repo.loadCalls.last().filter is ThreadDetailFilter.AuthorOnly)
        composeRule.onNodeWithContentDescription("退出只看").performClick()
        composeRule.waitForIdle()

        // 再次滚离顶部，点击顶栏标题空白区滚回 index == 0, offset == 0
        composeRule.runOnIdle { listState.requestScrollToItem(index = 12, scrollOffset = 40) }
        composeRule.waitForIdle()
        assertEquals(12, listState.firstVisibleItemIndex)

        composeRule.onNodeWithText("详情顶栏滚顶标题").performClick()
        composeRule.waitForIdle()
        assertEquals("点按详情顶栏标题必须滚回 index == 0", 0, listState.firstVisibleItemIndex)
        assertEquals("点按详情顶栏标题必须滚回 offset == 0", 0, listState.firstVisibleItemScrollOffset)
        assertEquals("点按标题不得误触发返回", 1, backClicks)
        assertEquals("点按标题不得误触发全屏", 1, fullScreenToggles)
    }

    @Test
    fun detailTopBar_reducedMotion_tapTitleSnapsToTopInZeroMs() = withAnimatorDurationScale(0f) {
        val repo = MultiFloorTestRepository(floorCount = 24, threadTitle = "减动效顶栏瞬移测试")
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        lateinit var listState: LazyListState

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "2602293", title = "减动效顶栏瞬移测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        runOnIdle { listState.requestScrollToItem(index = 14, scrollOffset = 60) }
        waitForIdle()
        assertEquals(14, listState.firstVisibleItemIndex)

        mainClock.autoAdvance = false
        try {
            onNodeWithText("减动效顶栏瞬移测试").performClick()
            mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            assertEquals("减动效下应 0ms 瞬移回 index == 0", 0, listState.firstVisibleItemIndex)
            assertEquals("减动效下应 0ms 瞬移回 offset == 0", 0, listState.firstVisibleItemScrollOffset)
        } finally {
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun sameThreadTargetPidAndOpenLastPage_jumpsViaPrependAnchorControllerAndPushesPositionStack() {
        val repo = SameThreadJumpTestRepository()
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        val hostVm = NextAppHostViewModel(StaticSettingsRepository(AppSettings()), repo)
        var hostBackDispatcher: OnBackPressedDispatcher? = null
        var navArgs by mutableStateOf(ThreadNavArgs(tid = "88100", title = "同帖跳楼与末页测试", fid = 2))
        lateinit var listState: LazyListState

        val linkDispatcher = DefaultPostLinkDispatcher(
            forumServerProvider = { "https://www.4d4y.com" },
            onOpenForum = {},
            onOpenThread = { tid, _ -> navArgs = ThreadNavArgs(tid = tid) },
            onOpenThreadLastPage = { tid -> navArgs = ThreadNavArgs(tid = tid, openLastPage = true) },
            onOpenPost = { tid, pid -> navArgs = ThreadNavArgs(tid = tid ?: "88100", targetPid = pid) },
            onOpenUserProfile = {},
            externalLinkOpener = ExternalLinkOpener { false },
        )

        composeRule.setContent {
            hostBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalPostLinkDispatcher provides linkDispatcher) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = navArgs,
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
        assertFalse("初次打开 PositionStack 必须为空", vm.uiState.value.canPopPositionStack)
        assertTrue("初始在 1 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse("前提：第 18 楼初始应在视口外", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" })

        // 1. R-E16 S2（本页同帖站内链接）：点击 1 楼正文中指向同 tid 第 18 楼（pid=1018）的站内链接
        composeRule.onNodeWithText("跳转到同帖第18楼链接").performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals("1018", navArgs.targetPid)
        assertTrue(
            "R-E16 S2：同帖点击指向第 18 楼的站内链接后必须经 PrependAnchorController 滚入 floor:1018",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" },
        )
        assertTrue("R-E16 S2：跳楼前必须先压入 PositionStack", vm.uiState.value.canPopPositionStack)

        // 2. R-E16 S2（跨页同帖站内链接）：跳至第 2 页目标楼（pid=2015），再连按两次 Back 逐级弹栈回 1 楼
        navArgs = ThreadNavArgs(tid = "88100", targetPid = "2015")
        composeRule.waitForIdle()
        assertEquals(listOf("2015"), repo.loadByPidCalls)
        assertTrue(
            "R-E16 S2：跨页同帖 targetPid 必须落位至 floor:2015",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:2015" },
        )
        assertEquals(2, vm.uiState.value.page)

        // 第 1 次返回 -> 回到第 1 页第 18 楼
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertTrue("第 1 次 Back 应回到跳前第 18 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1018" })
        assertTrue("PositionStack 还剩 1 级（1 楼）", vm.uiState.value.canPopPositionStack)

        // 第 2 次返回 -> 回到第 1 页第 1 楼，栈清空
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue("第 2 次 Back 应回到初始 1 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse("两级全部弹栈后 PositionStack 应为空", vm.uiState.value.canPopPositionStack)

        // 3. R-M7-G1 S2（多栏已打开同帖，经 NextAppHostViewModel 长按直达末页）：
        // NextAppHostViewModel 解析 loadLastPage 生成 targetPid = "3020"，已挂载的 ThreadDetailPane 响应跳到末页并压 PositionStack
        hostVm.openThread(ThreadNavArgs(tid = "88100", title = "同帖跳楼与末页测试", openLastPage = true))
        awaitWallCondition(5_000L, { "selected=${hostVm.state.value.selectedThread}" }) {
            hostVm.state.value.selectedThread?.targetPid == "3020"
        }
        navArgs = hostVm.state.value.selectedThread!!
        composeRule.waitForIdle()

        assertEquals(3, vm.uiState.value.page)
        assertTrue(
            "R-M7-G1 S2：多栏同帖长按直达末页后必须经 PrependAnchorController 落位至末楼 floor:3020",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" },
        )
        assertTrue("R-M7-G1 S2：跳末页前必须先压入 PositionStack", vm.uiState.value.canPopPositionStack)

        // 返回回第 1 页第 1 楼
        composeRule.runOnIdle { requireNotNull(hostBackDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.page)
        assertTrue("从末页按 Back 应回原 1 楼", listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:1001" })
        assertFalse(vm.uiState.value.canPopPositionStack)

        // 4. R-M7-G1 S2 直接传 openLastPage = true（无预解析 targetPid）也走同一跳末页并压栈路径
        navArgs = ThreadNavArgs(tid = "88100", title = "同帖跳楼与末页测试", openLastPage = true)
        composeRule.waitForIdle()
        assertEquals(3, vm.uiState.value.page)
        assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:3020" })
        assertTrue(vm.uiState.value.canPopPositionStack)
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun threadDetailPane_providesGalleryAndOpensViewerAtFloor2Image3WithHorizontalSwipe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        SuccessResult(
                            image = ColorImage(0xFF336699.toInt(), 800, 300),
                            request = chain.request,
                            dataSource = DataSource.MEMORY,
                        )
                    },
                )
            }
            .build()
        val renderer = ImageViewerRendererImpl(
            originalDownloader = object : OriginalImageDownloader {
                override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
            },
            mediaActions = object : ImageViewerMediaActions {
                override suspend fun saveImage(url: String, cachedFile: File?): Boolean = true
                override suspend fun shareImage(url: String, cachedFile: File?): Boolean = true
            },
        )
        val repo = MultiImageGalleryDetailRepository()
        val vm = ThreadDetailViewModel(repo, createIdleGateway())
        var activeViewerArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            // 故意不在测试外层提供 LocalThreadImageGallery，完全依赖 ThreadDetailPane 自身的 Provider 接线
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        ThreadDetailPane(
                            args = ThreadNavArgs(tid = "99001", title = "同帖多图横滑端到端测试", fid = 2),
                            chromeSpec = DetailChromeSpec(showBack = true),
                            onBack = {},
                            onOpenLogin = {},
                            onOpenImageViewer = { activeViewerArgs = it },
                            viewModel = vm,
                        )
                        activeViewerArgs?.let { launchArgs ->
                            renderer.Overlay(args = launchArgs, onDismiss = { activeViewerArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1 楼 2 张（0, 1）+ 2 楼 3 张（2, 3, 4）+ 3 楼 1 张（5）= 共 6 张图；点击第 2 楼第 3 张图（全局下标 4）
        val postImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        assertEquals("同帖 3 楼共 6 张图应全部加载可点", 6, postImages.fetchSemanticsNodes().size)
        postImages[4].performClick()
        composeRule.waitForIdle()

        assertNotNull("点击第 2 楼第 3 张图必须打开看图浮层", activeViewerArgs)
        assertEquals("图集应包含同帖全部 6 张图", 6, activeViewerArgs!!.urls.size)
        assertEquals("第 2 楼第 3 张图全局索引必须为 4", 4, activeViewerArgs!!.initialIndex)
        composeRule.onNodeWithText("第 2 楼 · 二楼摄影师").assertIsDisplayed()
        composeRule.onNodeWithText("5 / 6").assertIsDisplayed()

        // 左滑到第 3 楼的图（6 / 6）
        swipeViewerHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("6 / 6").assertIsDisplayed()

        // 右滑依次跨楼回到第 2 楼与第 1 楼的图（5 / 6 -> 4 / 6 -> 3 / 6 -> 2 / 6 -> 1 / 6）
        swipeViewerHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("5 / 6").assertIsDisplayed()
        swipeViewerHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("4 / 6").assertIsDisplayed()
        swipeViewerHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("3 / 6").assertIsDisplayed()
        swipeViewerHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("2 / 6").assertIsDisplayed()
        swipeViewerHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("1 / 6").assertIsDisplayed()
    }

    private fun swipeViewerHorizontally(fromFraction: Float, toFraction: Float) {
        composeRule.onRoot().performTouchInput {
            val startX = width * fromFraction
            val stepX = width * (toFraction - fromFraction) / 6f
            down(Offset(startX, height * 0.5f))
            repeat(6) { moveBy(Offset(stepX, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
    }

    private class RecordingTextToolbar : TextToolbar {
        var showMenuCalls: Int = 0
            private set
        var lastOnCopyRequested: (() -> Unit)? = null
            private set
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
            private set

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            showMenuCalls++
            lastOnCopyRequested = onCopyRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }

    private data class LoadCall(val page: Int, val filter: ThreadDetailFilter)

    private class MultiFloorTestRepository(
        private val floorCount: Int = 5,
        private val threadTitle: String = "测试主题",
    ) : ThreadDetailRepository {
        val loadCalls = CopyOnWriteArrayList<LoadCall>()
        val sampleFloor5 = PostFloor(
            postId = "9988",
            floor = 5,
            page = 1,
            uid = "4005",
            author = "坛友乙",
            avatarUrl = null,
            timePost = "2026-11-26 10:15",
            isWarned = false,
            postStatus = null,
            signature = null,
            poll = null,
            blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 5 楼可选正文片段 AlphaBeta")))),
        )

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadCalls += LoadCall(page, filter)
            val baseFloors = listOf(
                PostFloor(
                    postId = "5001", floor = 1, page = 1, uid = "4001", author = "楼主甲", avatarUrl = null,
                    timePost = "2026-11-26 10:00", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("1 楼主题纯文本内容")))),
                ),
                PostFloor(
                    postId = "5002", floor = 2, page = 1, uid = "4002", author = "坛友二", avatarUrl = null,
                    timePost = "2026-11-26 10:05", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("2 楼普通回复")))),
                ),
                PostFloor(
                    postId = "5003", floor = 3, page = 1, uid = "4003", author = "被警告坛友", avatarUrl = null,
                    timePost = "2026-11-26 10:08", isWarned = true, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("3 楼被警告内容")))),
                ),
                PostFloor(
                    postId = "5004", floor = 4, page = 1, uid = "4004", author = "坛友四", avatarUrl = null,
                    timePost = "2026-11-26 10:10", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("4 楼普通回复")))),
                ),
                sampleFloor5,
            ) + (6..floorCount).map { idx ->
                PostFloor(
                    postId = "${5000 + idx}", floor = idx, page = 1, uid = "${4000 + idx}", author = "坛友$idx", avatarUrl = null,
                    timePost = "2026-11-26 10:${idx.toString().padStart(2, '0')}", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 $idx 楼长正文填充内容用于测试顶栏滚回顶部与多楼层滚动。")))),
                )
            }
            val filtered = when (filter) {
                ThreadDetailFilter.All -> baseFloors
                is ThreadDetailFilter.AuthorOnly -> baseFloors.filter { it.uid == "4001" }
                is ThreadDetailFilter.UserOnly -> baseFloors.filter { it.uid == filter.uid }
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, threadTitle, 1, 1, filtered, filtered.size, sampleMeta(), true),
                filter = filter,
                threadAuthorId = "4001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "2602293", 1)
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

    private class SameThreadJumpTestRepository : ThreadDetailRepository {
        val loadByPidCalls = CopyOnWriteArrayList<String>()

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult = buildPage(tid, page)

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            buildPage(tid, 3, targetPostId = "3020")

        override suspend fun loadByPostId(
            pid: String,
            tid: String?,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadByPidCalls += pid
            val page = when {
                pid.startsWith("3") -> 3
                pid.startsWith("2") -> 2
                else -> 1
            }
            return buildPage(tid ?: "88100", page, targetPostId = pid)
        }

        private fun buildPage(tid: String, page: Int, targetPostId: String? = null): ThreadDetailLoadResult.Success {
            val basePid = page * 1000
            val floors = (1..20).map { idx ->
                val pid = "${basePid + idx}"
                val floorNum = (page - 1) * 20 + idx
                val blocks = if (page == 1 && idx == 1) {
                    listOf(
                        ContentBlock.Text(listOf(InlineSpan.Plain("1 楼引言正文"))),
                        ContentBlock.Text(
                            listOf(
                                InlineSpan.Link(
                                    label = "跳转到同帖第18楼链接",
                                    url = "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=$tid&pid=1018",
                                ),
                            ),
                        ),
                    )
                } else {
                    listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 $page 页第 $floorNum 楼内容填充，保证超出首屏高度。"))))
                }
                PostFloor(pid, floorNum, page, "${100 + idx}", "作者$floorNum", null, "2026-11-26 09:$idx", false, null, null, null, blocks)
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "同帖跳楼与末页测试", page, 3, floors, 20, sampleMeta(), true),
                filter = ThreadDetailFilter.All,
                threadAuthorId = "101",
                targetPostId = targetPostId,
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
            throw UnsupportedOperationException()
    }

    private class MultiImageGalleryDetailRepository : ThreadDetailRepository {
        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            val floors = listOf(
                PostFloor(
                    postId = "g_f1", floor = 1, page = 1, uid = "101", author = "一楼作者", avatarUrl = null,
                    timePost = "2026-11-26 09:00", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(
                        ContentBlock.Image("https://img.example.invalid/f1_1.jpg", null, 1024L),
                        ContentBlock.Image("https://img.example.invalid/f1_2.jpg", null, 1024L),
                    ),
                ),
                PostFloor(
                    postId = "g_f2", floor = 2, page = 1, uid = "102", author = "二楼摄影师", avatarUrl = null,
                    timePost = "2026-11-26 09:05", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(
                        ContentBlock.Image("https://img.example.invalid/f2_1.jpg", null, 1024L),
                        ContentBlock.Image("https://img.example.invalid/f2_2.jpg", null, 1024L),
                        ContentBlock.Image("https://img.example.invalid/f2_3.jpg", null, 1024L),
                    ),
                ),
                PostFloor(
                    postId = "g_f3", floor = 3, page = 1, uid = "103", author = "三楼坛友", avatarUrl = null,
                    timePost = "2026-11-26 09:10", isWarned = false, postStatus = null, signature = null, poll = null,
                    blocks = listOf(
                        ContentBlock.Image("https://img.example.invalid/f3_1.jpg", null, 1024L),
                    ),
                ),
            )
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "同帖多图横滑端到端测试", 1, 1, floors, 3, sampleMeta(), true),
                filter = filter,
                threadAuthorId = "101",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "99001", 1)
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

    private class StaticAuthRepository(account: SavedAccount?, state: AuthState) : AuthRepository {
        override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(account).asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(account)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(state).asStateFlow()
        override val sessionEpoch: Long = if (account != null) 1L else 0L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() = Unit
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private object NoOpFavoriteRepository : FavoriteRepository {
        override val favoriteTids: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
        override val attentionTids: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
        override fun isFavorite(tid: String): Boolean = false
        override fun isAttention(tid: String): Boolean = false
        override suspend fun getList(kind: FavoriteKind, page: Int, priority: AccessPriority): FavoriteListResult =
            throw UnsupportedOperationException()
        override suspend fun syncCache(kind: FavoriteKind, maxPages: Int, priority: AccessPriority): FavoriteSyncResult =
            throw UnsupportedOperationException()
        override suspend fun add(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult =
            throw UnsupportedOperationException()
        override suspend fun remove(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult =
            throw UnsupportedOperationException()
        override suspend fun removeByForm(kind: FavoriteKind, tid: String, formhash: String, priority: AccessPriority): FavoriteMutationResult =
            throw UnsupportedOperationException()
    }

    private class StaticSettingsRepository(initial: AppSettings) : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(initial)
        override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        override suspend fun setPureBlack(enabled: Boolean) = Unit
        override suspend fun setThemeSeed(themeSeed: String) = Unit
        override suspend fun setFontStep(step: Int) = Unit
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
        override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
        override suspend fun setShowStickyThreads(show: Boolean) = Unit
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
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

    private fun awaitWallCondition(timeoutMs: Long, describe: () -> String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
            if (predicate()) return
            Thread.sleep(20L)
        }
        assertTrue("等待条件超时（${describe()}）", predicate())
    }

    companion object {
        private val GBK = Charset.forName("GB18030")

        private fun gbkHtmlResponse(html: String): MockResponse {
            val buffer = Buffer().write(html.toByteArray(GBK))
            return MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=gbk")
                .body(buffer)
                .build()
        }

        private fun sampleMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "4001", username = "楼主甲"),
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
