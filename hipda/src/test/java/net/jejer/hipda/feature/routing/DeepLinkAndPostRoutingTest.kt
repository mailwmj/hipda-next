package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.DetailDrilldownEntry
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 外链、4 个桌面快捷方式、3 类系统通知直达、未知链接防循环、正文站内/站外链接分发与管理版块路由测试（验收 2）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeepLinkAndPostRoutingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortcutsAndNotifications_parseAndApplyCorrectlyWithoutLeakingIntentUsername() {
        val vm = NextAppHostViewModel()

        // 1. 4 个桌面快捷方式动作（与 res/xml/shortcuts.xml 一致）
        assertEquals(DeepLinkTarget.TodayNew, parseIncomingIntent(Intent(Constants.INTENT_NEW_POSTS)))
        vm.applyDeepLink(DeepLinkTarget.TodayNew)
        assertEquals(ForumListSource.TodayNew, vm.state.value.activeForumSource)
        assertEquals(ShellSidebarEntry.TodayNew, vm.state.value.selectedSidebarEntry)

        assertEquals(DeepLinkTarget.Search, parseIncomingIntent(Intent(Constants.INTENT_SEARCH)))
        vm.applyDeepLink(DeepLinkTarget.Search)
        assertEquals(ShellDestination.Search, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Search, vm.state.value.selectedSidebarEntry)

        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(Intent(Constants.INTENT_SMS)))
        vm.applyDeepLink(DeepLinkTarget.Sms)
        assertEquals(ShellDestination.Messages, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Sms, vm.state.value.selectedSidebarEntry)

        assertEquals(DeepLinkTarget.Favorites, parseIncomingIntent(Intent(Constants.INTENT_FAVORITE)))
        vm.applyDeepLink(DeepLinkTarget.Favorites)
        assertEquals(ShellDestination.Mine, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.FavoritesAndAttention, vm.state.value.selectedSidebarEntry)

        // 2. 3 类系统通知点击 Intent
        // 2a. 单条未读短消息含合法 uid（附带原始 EXTRA_USERNAME 必须被忽略脱敏为空串）
        val singleSmsIntent = Intent(Constants.INTENT_NOTIFICATION).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, 1)
            putExtra(Constants.EXTRA_THREAD_COUNT, 0)
            putExtra(Constants.EXTRA_UID, "10086")
            putExtra(Constants.EXTRA_USERNAME, "UNTRUSTED_PII_NAME")
        }
        assertEquals(DeepLinkTarget.SmsConversation("10086"), parseIncomingIntent(singleSmsIntent))
        vm.applyDeepLink(DeepLinkTarget.SmsConversation("10086"))
        assertEquals(ShellDestination.Messages, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Sms, vm.state.value.selectedSidebarEntry)
        assertEquals(SmsConversationArgs("10086", ""), vm.state.value.selectedSmsConversation)

        // 2b. 多条未读短消息（或单条但 uid 非法/混合提醒）-> 短消息列表
        val multiSmsIntent = Intent(Constants.INTENT_NOTIFICATION).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, 3)
            putExtra(Constants.EXTRA_THREAD_COUNT, 0)
            putExtra(Constants.EXTRA_UID, "10086")
        }
        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(multiSmsIntent))
        vm.applyDeepLink(DeepLinkTarget.Sms)
        assertEquals(ShellSidebarEntry.Sms, vm.state.value.selectedSidebarEntry)
        assertNull(vm.state.value.selectedSmsConversation)

        val badUidSingleSms = Intent(Constants.INTENT_NOTIFICATION).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, 1)
            putExtra(Constants.EXTRA_THREAD_COUNT, 0)
            putExtra(Constants.EXTRA_UID, "../evil")
        }
        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(badUidSingleSms))

        val mixedIntent = Intent(Constants.INTENT_NOTIFICATION).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, 1)
            putExtra(Constants.EXTRA_THREAD_COUNT, 2)
            putExtra(Constants.EXTRA_UID, "10086")
        }
        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(mixedIntent))

        // 2c. 仅提醒 -> Notify
        val notifyOnlyIntent = Intent(Constants.INTENT_NOTIFICATION).apply {
            putExtra(Constants.EXTRA_SMS_COUNT, 0)
            putExtra(Constants.EXTRA_THREAD_COUNT, 5)
        }
        assertEquals(DeepLinkTarget.Notify, parseIncomingIntent(notifyOnlyIntent))
        vm.applyDeepLink(DeepLinkTarget.Notify)
        assertEquals(ShellDestination.Messages, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Notify, vm.state.value.selectedSidebarEntry)
    }

    @Test
    fun externalForumLinks_parseAndResolveThreadLastPageAndPostWithoutTid() = runTest {
        assertEquals(
            DeepLinkTarget.Forum(6),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/forumdisplay.php?fid=6"))),
        )
        assertEquals(
            DeepLinkTarget.Thread("2602293", 3),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=2602293&page=3"))),
        )
        assertEquals(
            DeepLinkTarget.ThreadLastPage("3468951"),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=3468951"))),
        )
        assertEquals(
            DeepLinkTarget.Post("3474137", "74880921"),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=3474137&pid=74880921"))),
        )
        assertEquals(
            DeepLinkTarget.Post(null, "74880921"),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/gotopost.php?pid=74880921"))),
        )
        assertEquals(
            DeepLinkTarget.User("10000"),
            parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/space.php?uid=10000"))),
        )
        assertEquals(
            DeepLinkTarget.Thread("9001", 1),
            parseIncomingIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8080/forum/viewthread.php?tid=9001")),
                forumServer = "http://127.0.0.1:8080",
            ),
        )

        // 结合 FakeThreadDetailRepository 验证 Post(tid=null, pid) 与 ThreadLastPage(tid) 解析跳转
        val fakeRepo = FakeRoutingThreadDetailRepo(
            resolvedTidForPid = mapOf("74880921" to "3474137"),
            lastPageLastPidForTid = mapOf("3468951" to "999001"),
        )
        val harness = MultiPaneTestHarness()
        val vm = NextAppHostViewModel(harness.settingsRepo, fakeRepo)

        vm.applyDeepLink(DeepLinkTarget.Post(null, "74880921"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("3474137", vm.state.value.selectedThread?.tid)
        assertEquals("74880921", vm.state.value.selectedThread?.targetPid)

        vm.applyDeepLink(DeepLinkTarget.ThreadLastPage("3468951"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("3468951", vm.state.value.selectedThread?.tid)
        assertEquals("999001", vm.state.value.selectedThread?.targetPid)

        vm.applyDeepLink(DeepLinkTarget.User("10000"))
        assertEquals(listOf(DetailDrilldownEntry.UserProfile("10000", "")), vm.state.value.detailDrilldownStack)
    }

    @Test
    fun androidExternalLinkOpener_excludesSelfPackageToPreventIntentLoop() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val selfPkg = context.packageName
        val url = "https://www.4d4y.com/forum/misc.php?action=help"
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            addDataAuthority("www.4d4y.com", null)
        }

        // 1. 仅注册本应用自身 -> 必须拒绝拉起，0 startActivity，绝不自循环
        val selfResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = selfPkg
                name = "$selfPkg.ui.IntentActivity"
                exported = true
            }
        }
        shadowPm.addActivityIfNotPresent(android.content.ComponentName(selfPkg, "$selfPkg.ui.IntentActivity"))
        shadowPm.addIntentFilterForActivity(android.content.ComponentName(selfPkg, "$selfPkg.ui.IntentActivity"), filter)

        val launchedOnlySelf = mutableListOf<Intent>()
        val openerOnlySelf = AndroidExternalLinkOpener(context) { launchedOnlySelf += it }
        assertFalse("仅存在本应用处理器时必须拒绝自循环", openerOnlySelf.open(url))
        assertEquals("仅本应用时必须 0 startActivity", 0, launchedOnlySelf.size)

        // 2. 注册本应用 + 外部浏览器 Chrome -> 显式定向到 Chrome，排除本应用
        val chromeComponent = android.content.ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")
        shadowPm.addActivityIfNotPresent(chromeComponent)
        shadowPm.addIntentFilterForActivity(chromeComponent, filter)
        shadowPm.getInternalMutablePackageInfo("com.android.chrome").activities?.forEach { it.exported = true }

        val launchedWithChrome = mutableListOf<Intent>()
        val openerWithChrome = AndroidExternalLinkOpener(context) { launchedWithChrome += it }
        assertTrue("存在外部浏览器时应成功拉起", openerWithChrome.open(url))
        assertEquals(1, launchedWithChrome.size)
        assertEquals(chromeComponent, launchedWithChrome.single().component)
        assertTrue(launchedWithChrome.single().hasCategory(Intent.CATEGORY_BROWSABLE))

        // 3. 再增加第二个外部浏览器 Firefox -> 创建 Chooser 且所有目标都不含本应用
        val firefoxComponent = android.content.ComponentName("org.mozilla.firefox", "org.mozilla.firefox.App")
        shadowPm.addActivityIfNotPresent(firefoxComponent)
        shadowPm.addIntentFilterForActivity(firefoxComponent, filter)
        shadowPm.getInternalMutablePackageInfo("org.mozilla.firefox").activities?.forEach { it.exported = true }

        val launchedMulti = mutableListOf<Intent>()
        val openerMulti = AndroidExternalLinkOpener(context) { launchedMulti += it }
        assertTrue(openerMulti.open(url))
        assertEquals(1, launchedMulti.size)
        val chooser = launchedMulti.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val primary = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        @Suppress("DEPRECATION")
        val extras = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)?.mapNotNull { it as? Intent }.orEmpty()
        val allTargetPackages = (listOfNotNull(primary) + extras).mapNotNull { it.component?.packageName }.toSet()
        assertFalse("Chooser 目标绝不能包含本应用包名", selfPkg in allTargetPackages)
        assertEquals(setOf("com.android.chrome", "org.mozilla.firefox"), allTargetPackages)
        assertNotNull(selfResolve)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun unsupportedExternalDeepLink_showsFeedbackSnackbarAndOpensExternalBrowserOnAction() {
        val harness = MultiPaneTestHarness()
        val externalOpened = mutableListOf<String>()
        val unknownUrl = "https://www.4d4y.com/forum/misc.php?action=help"

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    externalLinkOpener = ExternalLinkOpener { externalOpened += it; true },
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            harness.hostViewModel.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(unknownUrl)))
        }
        composeRule.waitForIdle()

        // 绝不静默丢弃且绝不自动转抛系统 Intent 引发循环
        assertEquals("未知外链抵达时不得自动调用外部浏览器", 0, externalOpened.size)
        composeRule.onNodeWithText("无法识别该链接").assertIsDisplayed()
        composeRule.onNodeWithText("在浏览器中打开").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(unknownUrl), externalOpened)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun activityOnCreateAndOnNewIntent_consumeDeepLinkOnceAndDoNotReplayOnRotation() {
        val harness = MultiPaneTestHarness()
        DeepLinkLifecycleHarnessActivity.activeHarness = harness
        val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=2048"))

        val controller = Robolectric.buildActivity(DeepLinkLifecycleHarnessActivity::class.java, launchIntent).setup()
        try {
            val activityBefore = controller.get()
            pumpFrames()

            val host = ViewModelProvider(activityBefore)[NextAppHostViewModel::class.java]
            assertEquals("首次启动应消费深链打开 tid=2048", "2048", host.state.value.selectedThread?.tid)

            // 用户离开帖子切到版块 6
            host.selectForumSource(ForumListSource.Forum(6))
            pumpFrames()
            assertNull(host.state.value.selectedThread)
            assertEquals(ForumListSource.Forum(6), host.state.value.activeForumSource)

            // 触发真实配置变更（旋转重建 Activity）-> 不得重复消费旧启动 Intent
            val rotatedConfig = Configuration(activityBefore.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 749
                screenHeightDp = 559
            }
            controller.configurationChange(rotatedConfig)
            val activityAfter = controller.get()
            if (!activityAfter.window.decorView.isAttachedToWindow) controller.visible()
            pumpFrames()

            assertNotSame(activityBefore, activityAfter)
            assertNull("旋转重建后绝不能重复打开旧启动 Intent 的 tid=2048", host.state.value.selectedThread)
            assertEquals(ForumListSource.Forum(6), host.state.value.activeForumSource)

            // 热启动收到 onNewIntent（快捷方式 search）-> 仅消费一次
            controller.newIntent(Intent(Constants.INTENT_SEARCH))
            pumpFrames()
            assertEquals(ShellSidebarEntry.Search, host.state.value.selectedSidebarEntry)

            // 再切回版块 2 后再次旋转 -> 不得重复消费 search
            host.selectForumSource(ForumListSource.Forum(2))
            pumpFrames()
            val portraitConfig = Configuration(activityAfter.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 411
                screenHeightDp = 891
            }
            controller.configurationChange(portraitConfig)
            pumpFrames()
            assertEquals(ForumListSource.Forum(2), host.state.value.activeForumSource)
        } finally {
            controller.destroy()
            DeepLinkLifecycleHarnessActivity.activeHarness = null
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun postBodyLinksInsideNextAppRoot_routeInSiteInAppAndExternalToSystemOpener() {
        val harness = MultiPaneTestHarness()
        val externalOpened = mutableListOf<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    externalLinkOpener = ExternalLinkOpener { externalOpened += it; true },
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                    loginOverlayRenderer = object : net.jejer.hipda.feature.common.LoginOverlayRenderer {
                        @androidx.compose.runtime.Composable
                        override fun Content(
                            mode: net.jejer.hipda.feature.common.LoginOverlayMode,
                            onDismiss: () -> Unit,
                            onLoginSuccess: () -> Unit,
                        ) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                                    PostBodyRenderer(
                                        blocks = listOf(
                                            ContentBlock.Text(listOf(InlineSpan.Link("Thread2602293", "https://www.4d4y.com/forum/viewthread.php?tid=2602293"))),
                                            ContentBlock.Text(listOf(InlineSpan.Link("ForumFid6", "https://www.4d4y.com/forum/forumdisplay.php?fid=6"))),
                                            ContentBlock.Text(listOf(InlineSpan.Link("UserUid10000", "https://www.4d4y.com/forum/space.php?uid=10000"))),
                                            ContentBlock.Quote(
                                                authorAndTime = "引用作者",
                                                contentText = "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=3474137&pid=74880921",
                                                contentHtml = "",
                                                targetPid = null,
                                                targetTid = null,
                                            ),
                                            ContentBlock.Text(listOf(InlineSpan.Link("ExternalArticle", "https://example.com/article"))),
                                            ContentBlock.Text(listOf(InlineSpan.Email("qa@example.com"))),
                                        ),
                                        floorPostId = "post_in_root",
                                        listState = rememberLazyListState(),
                                        onOpenImageViewer = {},
                                        onLinkClick = null,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay() }
        composeRule.waitForIdle()

        // 1. 站外链接与合规 mailto 走系统外部打开器
        composeRule.onNodeWithText("ExternalArticle").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("qa@example.com").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.com/article", "mailto:qa@example.com"), externalOpened)

        // 2. 站内用户链接压入用户资料下钻，0 外部打开
        composeRule.onNodeWithText("UserUid10000").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(listOf(DetailDrilldownEntry.UserProfile("10000", "")), harness.hostViewModel.state.value.detailDrilldownStack)

        // 3. 引用块内站内跳楼链接应用内直达帖子+楼层，0 外部打开
        composeRule.onNodeWithText("https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=3474137&pid=74880921")
            .assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals("3474137", harness.hostViewModel.state.value.selectedThread?.tid)
        assertEquals("74880921", harness.hostViewModel.state.value.selectedThread?.targetPid)

        // 4. 站内帖子链接应用内打开帖子，0 外部打开
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Thread2602293").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals("2602293", harness.hostViewModel.state.value.selectedThread?.tid)

        // 5. 站内版块链接应用内切到版块 6，0 外部打开
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ForumFid6").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        assertEquals("站内链接绝不能调用外部浏览器", 2, externalOpened.size)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun forumManageForumsClick_singlePane_opensSettingsForumAndPostDetail() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("管理版块").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val state = harness.hostViewModel.state.value
        assertEquals(ShellDestination.Mine, state.selectedDestination)
        assertEquals(ShellSidebarEntry.Settings, state.selectedSidebarEntry)
        assertEquals(SettingsGroup.ForumAndPost, state.selectedSettingsGroup)
        assertTrue("单栏点击管理版块必须直接打开 ForumAndPost 分组详情", state.isSettingsDetailOpen)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun sidebarManageForumsClick_wideThreePane_opensSettingsForumAndPost() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            hasContentDescription("管理版块") and hasAnyAncestor(hasTestTag(HiShellTestTags.NavigationSidebar)),
        ).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val state = harness.hostViewModel.state.value
        assertEquals(ShellSidebarEntry.Settings, state.selectedSidebarEntry)
        assertEquals(SettingsGroup.ForumAndPost, state.selectedSettingsGroup)
        assertFalse("三栏右栏常驻展示设置详情，无需置单栏详情标记", state.isSettingsDetailOpen)
    }

    private fun pumpFrames() {
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    class DeepLinkLifecycleHarnessActivity : ComponentActivity() {
        private val hostViewModel: NextAppHostViewModel by lazy(LazyThreadSafetyMode.NONE) {
            ViewModelProvider(this, defaultViewModelProviderFactory)[NextAppHostViewModel::class.java]
        }

        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val harness = requireNotNull(activeHarness)
                    return when {
                        modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> harness.hostViewModel as T
                        else -> harness.vmFactory.create(modelClass)
                    }
                }
            }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val harness = requireNotNull(activeHarness)
            if (savedInstanceState == null) {
                hostViewModel.submitIncomingIntent(intent)
            }
            setContent {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    hostViewModel = hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            setIntent(intent)
            hostViewModel.submitIncomingIntent(intent)
        }

        companion object {
            internal var activeHarness: MultiPaneTestHarness? = null
        }
    }

    private class FakeRoutingThreadDetailRepo(
        private val resolvedTidForPid: Map<String, String> = emptyMap(),
        private val lastPageLastPidForTid: Map<String, String> = emptyMap(),
    ) : ThreadDetailRepository {
        private val emptyMeta = DiscuzPageMeta(DiscuzSessionInfo(true, "1", "tester"), "fh", null, PromptCounts())

        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            ThreadDetailError.InvalidPage("unused")

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val pid = lastPageLastPidForTid[tid] ?: return ThreadDetailError.InvalidPage("not found")
            val floor = PostFloor(
                postId = pid, floor = 50, page = 5, uid = "1", author = "author", avatarUrl = null,
                timePost = "刚刚", isWarned = false, postStatus = null, signature = null, poll = null,
                blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("末楼")))),
            )
            return ThreadDetailLoadResult.Success(
                ThreadDetailPage(tid = tid, fid = 2, title = "末页帖", page = 5, lastPage = 5, floors = listOf(floor), postsPerPageEstimate = 50, meta = emptyMeta, hasPostList = true),
            )
        }

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            val resolvedTid = tid ?: resolvedTidForPid[pid] ?: return ThreadDetailError.InvalidPage("not found")
            val floor = PostFloor(
                postId = pid, floor = 12, page = 2, uid = "1", author = "author", avatarUrl = null,
                timePost = "刚刚", isWarned = false, postStatus = null, signature = null, poll = null,
                blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("目标楼")))),
            )
            return ThreadDetailLoadResult.Success(
                ThreadDetailPage(tid = resolvedTid, fid = 2, title = "解析帖", page = 2, lastPage = 2, floors = listOf(floor), postsPerPageEstimate = 50, meta = emptyMeta, hasPostList = true),
                targetPostId = pid,
            )
        }

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = flowOf(emptyList())
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = flowOf(emptyList())
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> = emptyList()
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = emptyList()
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            ThreadDetailError.InvalidInput("unused")
    }
}
