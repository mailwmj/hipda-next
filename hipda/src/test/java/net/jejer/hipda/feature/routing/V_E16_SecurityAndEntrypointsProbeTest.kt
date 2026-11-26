package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.PatternMatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.SmsClearOutcome
import net.jejer.hipda.data.message.SmsRepository
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.data.user.UserProfileResult
import net.jejer.hipda.data.user.UserRepository
import net.jejer.hipda.data.user.UserThreadsResult
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.messages.SmsBubbleSegment
import net.jejer.hipda.feature.messages.SmsConversationScreen
import net.jejer.hipda.feature.messages.SmsConversationViewModel
import net.jejer.hipda.feature.messages.SmsTextPart
import net.jejer.hipda.feature.messages.parseSmsBubbleContent
import net.jejer.hipda.feature.messages.parseSmsBubblePlainText
import net.jejer.hipda.feature.profile.ProfileUrlSanitizer
import net.jejer.hipda.feature.profile.UserProfileScreen
import net.jejer.hipda.feature.profile.UserProfileViewModel
import net.jejer.hipda.feature.search.SearchPane
import net.jejer.hipda.feature.search.SearchViewModel
import net.jejer.hipda.feature.search.dispatchAllowedForumLink
import net.jejer.hipda.feature.search.resolveAllowedForumLink
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.feature.threaddetail.chrome.ThreadBottomToolbar
import net.jejer.hipda.feature.threaddetail.chrome.buildSafeThreadUrl
import net.jejer.hipda.feature.threaddetail.chrome.copyThreadLinkPlainText
import net.jejer.hipda.feature.threaddetail.chrome.openExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.chrome.sanitizeExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.chrome.shareExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.media.OfflineTestImageLoader
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.user.UserProfile
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.GlassDegradationReason
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 独立验证卡 V-E16：
 * - 探针 1：全路径恶意 Scheme 对抗矩阵（0 导航、0 startActivity/openUri、0 网络请求）；
 * - 补充探针 (6)：未知外链回落浏览器时 Intent 选择器严格排除本应用自身；
 * - 补充探针 (7)：用户资料签名、短消息、搜索结果、帖子正文、底部工具条 5 个入口各投 `javascript:` 与 `https://user@evil` 全部拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V_E16_SecurityAndEntrypointsProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(context)
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    private fun dangerousLinkVectors(mockBaseUrl: String): List<String> = listOf(
        "javascript:alert(1)",
        "JAVASCRIPT:alert(document.cookie)",
        "JaVaScRiPt://comment/%0aalert(1)",
        "intent://scan/#Intent;scheme=zxing;package=com.evil.app;end",
        "INTENT://example.com#Intent;end",
        "file:///etc/passwd",
        "FILE:///sdcard/secret.txt",
        "content://settings/secure",
        "data:text/html,<script>alert(1)</script>",
        "tel:10086",
        "vbscript:msgbox(1)",
        "blob:https://www.4d4y.com/1234-5678",
        "about:blank",
        "market://details?id=com.evil.app",
        "android.resource://net.jejer.hipda.next/raw/secret",
        " javascript:alert(1)",
        "https://www.4d4y.com/forum/viewthread.php?tid=1 ",
        "https://www.4d4y.com/forum/viewthread.php?tid=1\n",
        "https://www.4d4y.com/forum/viewthread.php?tid=1\u0000",
        "https://www.4d4y.com/forum/viewthread.php?tid=1\u200B",
        "https://www.4d4y.com/forum/viewthread.php?tid=1\uFEFF",
        "ht\ttp://www.4d4y.com/forum/viewthread.php?tid=1",
        "http://",
        "https:///evil.com/path",
        "//www.4d4y.com/forum/viewthread.php?tid=1",
        "https:www.4d4y.com/forum/viewthread.php?tid=1",
        "https://www.4d4y.com@evil.com/forum/viewthread.php?tid=1",
        "https://user@evil/path",
        "https://user:pass@evil.com/path",
        "https://evil.com\\@www.4d4y.com/forum/viewthread.php?tid=1",
        "mailto:test@example.com?subject=evil",
        "mailto:test@example.com\ncc=evil@example.com",
        "mailto:test@example.com#frag",
        "mailto:@example.com",
        "mailto:admin@localhost",
        "mailto:a..b@example.com",
        "MAILTO:test@example.com?body=hi",
        "${mockBaseUrl.trimEnd('/')}/file.zip\u0000",
    )

    @Test
    fun probe1_maliciousSchemeMatrix_blocksAllEntrypointsWithZeroNavStartActivityAndNetwork() {
        val mockBaseUrl = mockServer.url("/").toString()
        val vectors = dangerousLinkVectors(mockBaseUrl)
        val attachmentOnlyVectors = vectors + listOf("mailto:valid@example.com", "MailTo:support@example.com")

        val harness = MultiPaneTestHarness()
        val openedExternal = mutableListOf<String>()
        val openedUris = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }

        // 将全部危险链接注入同一楼层的正文块、引用块与附件块，经真实 NextAppRoot -> ThreadDetailPane -> PostBodyRenderer 渲染
        val combinedTextSpans = vectors.flatMapIndexed { idx, payload ->
            listOf(
                InlineSpan.Link("L$idx", payload),
                InlineSpan.Plain(" "),
            )
        }
        val blocks = buildList {
            add(ContentBlock.Text(combinedTextSpans))
            add(
                ContentBlock.Quote(
                    authorAndTime = "quote_author",
                    contentHtml = "quote",
                    contentText = "Quote js javascript:alert(9) intent://x https://user@evil.com/q mailto:bad@evil.com?x=1",
                    targetTid = null,
                    targetPid = null,
                ),
            )
            attachmentOnlyVectors.forEachIndexed { idx, payload ->
                add(
                    ContentBlock.Attachment(
                        title = "att_$idx.zip",
                        url = payload,
                        description = "desc_$idx",
                    ),
                )
            }
        }

        val probeDetailRepo = object : ThreadDetailRepository by harness.threadDetailRepo {
            override suspend fun loadPage(
                tid: String,
                page: Int,
                filter: ThreadDetailFilter,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "恶意矩阵测试帖",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(
                        PostFloor(
                            postId = "5001",
                            floor = 1,
                            page = 1,
                            uid = "99",
                            author = "attacker",
                            avatarUrl = null,
                            timePost = "2026-11-26 10:00",
                            isWarned = false,
                            postStatus = null,
                            signature = null,
                            poll = null,
                            blocks = blocks,
                        ),
                    ),
                    postsPerPageEstimate = null,
                    meta = offlinePageMeta(),
                    hasPostList = true,
                ),
                filter = filter,
                forumName = "Discovery",
                threadAuthorId = "99",
            )
        }
        val hostVm = NextAppHostViewModel(harness.settingsRepo, probeDetailRepo)
        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> harness.forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(probeDetailRepo, harness.gateway) as T
                else -> error("Unexpected VM: $modelClass")
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }

        val downloader = OkHttpAttachmentDownloader(
            callFactory = OkHttpClient(),
            saver = object : AttachmentSaver {
                override suspend fun save(fileName: String, mimeType: String, writeTo: suspend (java.io.OutputStream) -> Long): SavedAttachment? =
                    error("Downloader must never reach saver on rejected URLs")
            },
            hasLegacyStoragePermission = { true },
            sdkInt = 34,
        )

        var navCalls = 0
        val dispatcher = DefaultPostLinkDispatcher(
            forumServerProvider = { mockBaseUrl },
            onOpenForum = { navCalls++ },
            onOpenThread = { _, _ -> navCalls++ },
            onOpenThreadLastPage = { navCalls++ },
            onOpenPost = { _, _ -> navCalls++ },
            onOpenUserProfile = { navCalls++ },
            externalLinkOpener = ExternalLinkOpener { url ->
                openedExternal += url
                true
            },
        )

        // 纯入口与分发层逐条注入
        vectors.forEach { payload ->
            assertNull("parseDeepLinkUrl must reject: $payload", parseDeepLinkUrl(payload, mockBaseUrl))
            assertNull(
                "parseIncomingIntent ACTION_VIEW must reject: $payload",
                parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(payload)), mockBaseUrl),
            )
            assertNull(
                "parseIncomingIntent EXTRA_DEEP_LINK_URL must reject: $payload",
                parseIncomingIntent(Intent("custom").putExtra(EXTRA_DEEP_LINK_URL, payload), mockBaseUrl),
            )
            assertFalse("DefaultPostLinkDispatcher must reject: $payload", dispatcher.dispatch(payload))
        }
        assertNull(parseDeepLinkUrl("mailto:valid@example.com", mockBaseUrl))
        assertNull(parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:valid@example.com")), mockBaseUrl))

        attachmentOnlyVectors.forEach { payload ->
            val res = runBlocking { downloader.download(payload, "safe.bin") }
            assertEquals("AttachmentDownloader must return InvalidUrl for: $payload", AttachmentDownloadResult.InvalidUrl, res)
        }

        // 先打开测试帖并等待真实 ThreadDetailPane + PostBodyRenderer 渲染完成，再向 NextAppRoot 投递全套恶意 Intent
        hostVm.openThread(ThreadNavArgs("5001", "恶意矩阵测试帖"))
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                            notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                            attachmentDownloader = downloader,
                            externalLinkOpener = ExternalLinkOpener { url ->
                                openedExternal += url
                                true
                            },
                            hostViewModel = hostVm,
                            forumViewModel = harness.forumViewModel,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val stateBeforeMaliciousIntents = hostVm.state.value

        vectors.forEach { payload ->
            hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(payload)))
        }
        composeRule.waitForIdle()
        assertEquals("Malicious ACTION_VIEW intents must not mutate app state", stateBeforeMaliciousIntents, hostVm.state.value)

        // 验证 ThreadDetailPane 渲染出的正文与引用不含任何 LinkAnnotation 点击动作
        val textNode = composeRule.onNodeWithText("L0", substring = true).fetchSemanticsNode()
        assertFalse("PostBodyRenderer text must NOT contain OnClick semantics", SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).matches(textNode))
        composeRule.onNodeWithText("Quote js", substring = true).performClick()
        composeRule.waitForIdle()

        // 逐条滚入并校验 PostBodyRenderer 内全部危险及 mailto: 附件卡片均不可点
        attachmentOnlyVectors.indices.forEach { idx ->
            val node = composeRule.onNodeWithText("att_$idx.zip")
            node.performScrollTo()
            node.assertIsDisplayed()
            assertFalse(
                "Attachment $idx (${attachmentOnlyVectors[idx]}) must NOT have OnClick semantics",
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).matches(node.fetchSemanticsNode()),
            )
        }

        val appShadow = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        assertNull("Zero startActivity expected", appShadow.nextStartedActivity)
        assertEquals("Zero onNav callbacks expected", 0, navCalls)
        assertTrue("Zero external opener calls expected: $openedExternal", openedExternal.isEmpty())
        assertTrue("Zero UriHandler calls expected: $openedUris", openedUris.isEmpty())
        assertEquals("Zero MockWebServer requests expected", 0, mockServer.requestCount)
    }

    @Test
    fun probe6_unknownExternalLinkFallback_excludesSelfAppInChooserAndPreventsLoop() {
        val pm = context.packageManager
        val shadowPm = shadowOf(pm)
        val selfPkg = context.packageName
        val selfComponent = ComponentName(selfPkg, "net.jejer.hipda.ng.IntentActivity")
        val browserA = ComponentName("org.mozilla.firefox", "org.mozilla.firefox.App")
        val browserB = ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")

        fun registerViewHandler(component: ComponentName, host: String) {
            val info = ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                exported = true
            }
            @Suppress("DEPRECATION")
            shadowPm.addOrUpdateActivity(info)
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority(host, null)
                addDataPath("/forum/", PatternMatcher.PATTERN_PREFIX)
            }
            shadowPm.addIntentFilterForActivity(component, filter)
        }

        fun registerAppBrowser(component: ComponentName) {
            val info = ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                exported = true
            }
            @Suppress("DEPRECATION")
            shadowPm.addOrUpdateActivity(info)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_BROWSER)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            shadowPm.addIntentFilterForActivity(component, filter)
        }

        val unknownForumUrl = "https://www.4d4y.com/forum/viewthread.php?action=printable&mystery=1"
        val launched = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(context) { launched += it }

        // 场景 A：自身 + 2 个外部浏览器都匹配 ACTION_VIEW -> 构造 Chooser，只含 2 个外部浏览器，严格排除本应用
        registerViewHandler(selfComponent, "www.4d4y.com")
        registerViewHandler(browserA, "www.4d4y.com")
        registerViewHandler(browserB, "www.4d4y.com")
        assertTrue(opener.open(unknownForumUrl))
        assertEquals(1, launched.size)
        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val primary = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        @Suppress("DEPRECATION")
        val initialIntents = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)?.filterIsInstance<Intent>().orEmpty()
        val targetComponents = (listOfNotNull(primary) + initialIntents).mapNotNull { it.component }
        assertEquals(setOf(browserA, browserB), targetComponents.toSet())
        assertTrue("Chooser targets must strictly exclude self package", targetComponents.none { it.packageName == selfPkg })

        // 场景 B：仅注册 self + browserA（将 browserB 改为非导出/禁用，避免 ShadowPM removePackage 连带影响）-> 直接显式启动 browserA，不走自环
        launched.clear()
        @Suppress("DEPRECATION")
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = browserB.packageName
                name = browserB.className
                exported = false
            },
        )
        assertTrue(opener.open(unknownForumUrl))
        assertEquals(1, launched.size)
        assertEquals(browserA, launched.single().component)

        // 场景 C：ACTION_VIEW 仅自身匹配，外部浏览器仅通过 CATEGORY_APP_BROWSER 可见 -> 回落显式浏览器并排除自身
        launched.clear()
        @Suppress("DEPRECATION")
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = browserA.packageName
                name = browserA.className
                exported = false
            },
        )
        val browserC = ComponentName("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.SBrowserMainActivity")
        val browserD = ComponentName("com.duckduckgo.mobile.android", "com.duckduckgo.app.browser.BrowserActivity")
        registerAppBrowser(browserC)
        registerAppBrowser(browserD)
        assertTrue(opener.open(unknownForumUrl))
        val fallbackChooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, fallbackChooser.action)
        @Suppress("DEPRECATION")
        val fallbackPrimary = fallbackChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        @Suppress("DEPRECATION")
        val fallbackExtras = fallbackChooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)?.filterIsInstance<Intent>().orEmpty()
        val fallbackComponents = (listOfNotNull(fallbackPrimary) + fallbackExtras).mapNotNull { it.component }
        assertEquals(setOf(browserC, browserD), fallbackComponents.toSet())
        assertTrue(fallbackComponents.none { it.packageName == selfPkg })

        // 场景 D：仅有本应用、无任何外部浏览器 -> 返回 false、0 startActivity，且在 NextAppRoot 中提示“未找到可用的浏览器”
        launched.clear()
        listOf(browserC, browserD).forEach { comp ->
            @Suppress("DEPRECATION")
            shadowPm.addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = comp.packageName
                    name = comp.className
                    exported = false
                },
            )
        }
        assertFalse(opener.open(unknownForumUrl))
        assertTrue("Zero startActivity when only self matches", launched.isEmpty())

        val harness = MultiPaneTestHarness()
        val noBrowserOpener = AndroidExternalLinkOpener(context) { launched += it }
        composeRule.setContent {
            ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                        externalLinkOpener = noBrowserOpener,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        harness.hostViewModel.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(unknownForumUrl)))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.routing_deeplink_unsupported_url)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.routing_action_open_in_browser)).performClick()
        composeRule.waitForIdle()

        assertTrue("Self-loop Intent must NEVER be launched", launched.isEmpty())
        composeRule.onNodeWithText(context.getString(R.string.routing_error_no_browser)).assertIsDisplayed()
    }

    @Test
    fun probe7_fiveEntrypoints_rejectJavascriptAndUserInfoLinksCompletely() {
        val jsPayload = "javascript:alert(1)"
        val userInfoPayload = "https://user@evil/forum/viewthread.php?tid=1"
        val uriCalls = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                uriCalls += uri
            }
        }

        // ---------------- 入口 1：用户资料签名 ----------------
        val profileExternalCalls = mutableListOf<String>()
        val sigLine1 = "[url=$jsPayload]签名JS标签[/url] 和 $jsPayload"
        val sigLine2 = "[url=$userInfoPayload]签名UserInfo标签[/url] 和 $userInfoPayload"
        val ann1 = ProfileUrlSanitizer.buildSafeAnnotatedText(sigLine1, Color.Blue) { profileExternalCalls += it }
        val ann2 = ProfileUrlSanitizer.buildSafeAnnotatedText(sigLine2, Color.Blue) { profileExternalCalls += it }
        assertTrue("Profile signature must produce 0 link annotations for javascript:", ann1.getLinkAnnotations(0, ann1.length).isEmpty())
        assertTrue("Profile signature must produce 0 link annotations for user@evil", ann2.getLinkAnnotations(0, ann2.length).isEmpty())

        val profileRepo = object : UserRepository {
            override suspend fun getUserProfile(uid: String, priority: AccessPriority): UserProfileResult = UserProfileResult.Success(
                UserProfile(
                    uid = uid,
                    username = "tester",
                    isOnline = true,
                    avatarUrl = null,
                    formhash = "fh",
                    detail = "个人签名\n$sigLine1\n$sigLine2",
                    meta = offlinePageMeta(),
                ),
            )
            override suspend fun getUserThreads(uid: String, page: Int, searchId: String?, priority: AccessPriority): UserThreadsResult =
                throw UnsupportedOperationException()
        }
        val blacklistRepo = object : BlacklistRepository {
            override val blockedUsernames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
            override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null)
            override fun isBlocked(username: String): Boolean = false
            override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult = throw UnsupportedOperationException()
            override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult =
                throw UnsupportedOperationException()
            override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult =
                throw UnsupportedOperationException()
        }
        val profileVm = UserProfileViewModel("77", "tester", profileRepo, blacklistRepo)

        // ---------------- 入口 2：短消息 ----------------
        val smsHtml = "<a href=\"$jsPayload\">短消息JS</a> <a href=\"$userInfoPayload\">短消息UserInfo</a>"
        val parsedHtml = parseSmsBubbleContent(smsHtml)
        val parsedPlain = parseSmsBubblePlainText("$jsPayload $userInfoPayload")
        val smsLinks = (parsedHtml.segments + parsedPlain.segments)
            .filterIsInstance<SmsBubbleSegment.Text>()
            .flatMap { it.parts }
            .filterIsInstance<SmsTextPart.Link>()
        assertTrue("SMS must produce 0 link parts for javascript: and user@evil", smsLinks.isEmpty())

        val authRepo = object : AuthRepository {
            private val acc = SavedAccount("42", "HiUser", "0".repeat(32), lastLoginAt = 1L)
            override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(acc)
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOf(acc))
            override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn(acc))
            override val sessionEpoch: Long = 1L
            override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
            override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
            override suspend fun logout() = Unit
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
        }
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val smsRepo = object : SmsRepository {
            override val cooldownUntilMs: StateFlow<Long> = MutableStateFlow(0L)
            override fun getRemainingCooldownSeconds(): Int = 0
            override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority): MessageResult<SmsListPage> =
                throw UnsupportedOperationException()
            override suspend fun getConversationDetail(uid: String, priority: AccessPriority): MessageResult<SmsDetailPage> =
                MessageResult.Success(
                    SmsDetailPage(
                        myUid = "42",
                        myUsername = "HiUser",
                        formhash = "fh",
                        messages = listOf(
                            SmsMessageItem("88", "Peer", null, "10:00", smsHtml, isNew = false, isMine = false),
                            SmsMessageItem("88", "Peer", null, "10:01", "纯文本短消息 $jsPayload $userInfoPayload", isNew = false, isMine = false),
                        ),
                        meta = offlinePageMeta(),
                    ),
                )
            override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority): SmsSendOutcome =
                throw UnsupportedOperationException()
            override suspend fun clearConversation(uid: String, priority: AccessPriority): SmsClearOutcome =
                throw UnsupportedOperationException()
        }
        val smsVm = SmsConversationViewModel(smsRepo, authRepo, gateway)

        // ---------------- 入口 3：搜索结果 ----------------
        assertNull(resolveAllowedForumLink(jsPayload))
        assertNull(resolveAllowedForumLink(userInfoPayload))
        var searchNavCalls = 0
        assertFalse(dispatchAllowedForumLink(jsPayload, AppSettings.DEFAULT_FORUM_SERVER, { searchNavCalls++ }, { searchNavCalls++ }, { _, _ -> searchNavCalls++ }))
        assertFalse(dispatchAllowedForumLink(userInfoPayload, AppSettings.DEFAULT_FORUM_SERVER, { searchNavCalls++ }, { searchNavCalls++ }, { _, _ -> searchNavCalls++ }))

        val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean): SearchResult =
                SearchResult.Success(page, fullText, SearchPage(null, 1, emptyList(), offlinePageMeta()))
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult =
                SearchResult.Success(page, false, SearchPage(null, 1, emptyList(), offlinePageMeta()))
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String): List<String> = emptyList()
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
            override suspend fun restoreSearchHistory(query: String, index: Int): List<String> = emptyList()
            override suspend fun clearSearchHistory() = Unit
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        }
        val settingsFlow = MutableStateFlow(AppSettings())
        val searchVm = SearchViewModel(
            searchRepository = searchRepo,
            settingsRepository = object : SettingsRepository by MultiPaneTestHarness().settingsRepo {
                override val settings: Flow<AppSettings> = settingsFlow
            },
            authRepository = authRepo,
            siteAccessGateway = gateway,
        )

        // ---------------- 入口 4：帖子正文 ----------------
        val postBlocks = listOf(
            ContentBlock.Text(listOf(InlineSpan.Link("正文JS链接", jsPayload))),
            ContentBlock.Text(listOf(InlineSpan.Link("正文UserInfo链接", userInfoPayload))),
            ContentBlock.Quote("a", "q", "引用内 $jsPayload 与 $userInfoPayload", null, null),
            ContentBlock.Attachment(title = "正文JS附件.zip", url = jsPayload, description = null),
            ContentBlock.Attachment(title = "正文UserInfo附件.zip", url = userInfoPayload, description = null),
        )
        val postExternalOpens = mutableListOf<String>()

        // ---------------- 入口 5：底部工具条 ----------------
        assertNull(sanitizeExternalHttpUrl(jsPayload))
        assertNull(sanitizeExternalHttpUrl(userInfoPayload))
        assertNull(buildSafeThreadUrl(jsPayload, "123", 1))
        assertNull(buildSafeThreadUrl("https://user@evil", "123", 1))
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "KEEP_ME"))
        val toolbarIntents = mutableListOf<Intent>()
        assertFalse(copyThreadLinkPlainText(context, "link", jsPayload))
        assertFalse(copyThreadLinkPlainText(context, "link", userInfoPayload))
        assertFalse(openExternalHttpUrl(context, jsPayload) { toolbarIntents += it })
        assertFalse(openExternalHttpUrl(context, userInfoPayload) { toolbarIntents += it })
        assertFalse(shareExternalHttpUrl(context, "t", jsPayload, "share") { toolbarIntents += it })
        assertFalse(shareExternalHttpUrl(context, "t", userInfoPayload, "share") { toolbarIntents += it })
        assertEquals("KEEP_ME", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(toolbarIntents.isEmpty())

        var activeEntry by mutableIntStateOf(0)

        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                    CompositionLocalProvider(
                        LocalUriHandler provides recordingUriHandler,
                        LocalPaneSnackbarController provides controller,
                        LocalHiImageLoader provides OfflineTestImageLoader { _ -> error("offline") },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            when (activeEntry) {
                                0 -> UserProfileScreen(
                                    viewModel = profileVm,
                                    showBack = false,
                                    onBack = {},
                                    onOpenThread = { searchNavCalls++ },
                                    onOpenSmsConversation = { searchNavCalls++ },
                                    onOpenLogin = {},
                                    contentPadding = PaddingValues(0.dp),
                                    onOpenExternalLink = { profileExternalCalls += it },
                                )
                                1 -> SmsConversationScreen(
                                    args = SmsConversationArgs("88", "Peer"),
                                    showBack = false,
                                    onBack = {},
                                    onOpenUserProfile = { _, _ -> searchNavCalls++ },
                                    onOpenLogin = {},
                                    contentPadding = PaddingValues(0.dp),
                                    viewModel = smsVm,
                                )
                                2 -> SearchPane(
                                    viewModel = searchVm,
                                    showBack = false,
                                    onBack = {},
                                    onOpenThread = { searchNavCalls++ },
                                    onOpenForum = { searchNavCalls++ },
                                    onOpenUserProfile = { _, _ -> searchNavCalls++ },
                                    onOpenLogin = {},
                                )
                                3 -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    PostBodyRenderer(
                                        blocks = postBlocks,
                                        floorPostId = "9001",
                                        listState = rememberLazyListState(),
                                        onOpenImageViewer = {},
                                        onLinkClick = { postExternalOpens += it },
                                    )
                                }
                                4 -> ThreadBottomToolbar(
                                    page = 1,
                                    lastPage = 3,
                                    isFavorited = false,
                                    isAttention = false,
                                    glassState = rememberHiGlassState(),
                                    suppressBottomGlass = false,
                                    isJumpBarExpanded = false,
                                    onExpandedChange = {},
                                    onReplyClick = {},
                                    onJumpToPage = {},
                                    onToggleFavorite = {},
                                    onRefreshClick = {},
                                    onToggleAttention = {},
                                    onCopyLinkClick = {
                                        copyThreadLinkPlainText(context, "copy", buildSafeThreadUrl(jsPayload, "123"))
                                        copyThreadLinkPlainText(context, "copy", buildSafeThreadUrl("https://user@evil", "123"))
                                    },
                                    onOpenInBrowserClick = {
                                        openExternalHttpUrl(context, buildSafeThreadUrl(jsPayload, "123")) { toolbarIntents += it }
                                        openExternalHttpUrl(context, buildSafeThreadUrl("https://user@evil", "123")) { toolbarIntents += it }
                                    },
                                    onShareClick = {
                                        shareExternalHttpUrl(context, "t", buildSafeThreadUrl(jsPayload, "123"), "s") { toolbarIntents += it }
                                        shareExternalHttpUrl(context, "t", buildSafeThreadUrl("https://user@evil", "123"), "s") { toolbarIntents += it }
                                    },
                                )
                            }
                            HiSnackbarHost(snackbarHostState, 0.dp)
                        }
                    }
                }
            }
        }

        // 入口 1：用户资料签名
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("签名JS标签", substring = true).onLast().performClick()
        composeRule.onAllNodesWithText("签名UserInfo标签", substring = true).onLast().performClick()

        // 入口 2：短消息（HTML 与纯文本气泡）
        activeEntry = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithText("短消息JS", substring = true).performClick()
        composeRule.onNodeWithText("短消息UserInfo", substring = true).performClick()
        composeRule.onNodeWithText("纯文本短消息", substring = true).performClick()

        // 入口 3：搜索结果
        activeEntry = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.common_search_hint)).performTextInput(jsPayload)
        composeRule.waitForIdle()
        assertNull(searchVm.uiState.value.resolvedLink)
        composeRule.onNodeWithText(context.getString(R.string.search_open_link)).assertDoesNotExist()
        searchVm.clearQuery()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.common_search_hint)).performTextInput(userInfoPayload)
        composeRule.waitForIdle()
        assertNull(searchVm.uiState.value.resolvedLink)
        composeRule.onNodeWithText(context.getString(R.string.search_open_link)).assertDoesNotExist()

        // 入口 4：帖子正文
        activeEntry = 3
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正文JS链接").performTouchInput { click(Offset(24f, centerY)) }
        composeRule.onNodeWithText("正文UserInfo链接").performTouchInput { click(Offset(24f, centerY)) }
        composeRule.onNodeWithText("引用内", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        assertFalse(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).matches(composeRule.onNodeWithText("正文JS附件.zip").fetchSemanticsNode()))
        assertFalse(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).matches(composeRule.onNodeWithText("正文UserInfo附件.zip").fetchSemanticsNode()))

        // 入口 5：底部工具条
        activeEntry = 4
        composeRule.waitForIdle()
        listOf(
            R.string.thread_detail_more_copy_link,
            R.string.thread_detail_more_open_in_browser,
            R.string.thread_detail_more_share,
        ).forEach { menuRes ->
            composeRule.onNodeWithContentDescription(context.getString(R.string.thread_detail_toolbar_more)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(menuRes)).performClick()
            composeRule.waitForIdle()
        }

        assertTrue("Zero UriHandler.openUri calls across all 5 entrypoints: $uriCalls", uriCalls.isEmpty())
        assertTrue("Zero profile external link calls: $profileExternalCalls", profileExternalCalls.isEmpty())
        assertTrue("Zero post link calls: $postExternalOpens", postExternalOpens.isEmpty())
        assertTrue("Zero toolbar intents: $toolbarIntents", toolbarIntents.isEmpty())
        assertEquals("KEEP_ME", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(0, searchNavCalls)
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
    }
}
