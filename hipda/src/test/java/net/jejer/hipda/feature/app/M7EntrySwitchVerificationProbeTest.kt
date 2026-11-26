package net.jejer.hipda.feature.app

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.R
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.data.settings.SettingsPreferenceKeys
import net.jejer.hipda.di.SettingsModule
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.routing.DeepLinkTarget
import net.jejer.hipda.feature.routing.EXTRA_DEEP_LINK_URL
import net.jejer.hipda.feature.routing.parseIncomingIntent
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.service.AndroidNotificationPoster
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.utils.Constants
import okhttp3.OkHttp
import okio.Buffer
import okio.buffer
import okio.sink
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
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPendingIntent
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser

/**
 * M7-1 独立验收探针（面向 V-M7-1 探针 1 与探针 2）：
 * 1. 清单、包名、`.ng.IntentActivity` 别名 5 条 pathPrefix、`shortcuts.xml` 4 项、`<queries>` 与旧 Activity 隔离；
 * 2. 3 类通知 PendingIntent 经真实 [AndroidNotificationPoster] 输出且严格不带 `EXTRA_USERNAME`；
 * 3. 真实 [MainActivity]（合入最新 `origin/main` 后）承载 4 快捷方式、3 类通知、5 条外链 `ACTION_VIEW` 与不可识别链接、
 *    旋转 `recreate()` 不重放、`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 从最近任务恢复不重放、危险 scheme 矩阵与坏 Parcelable 对抗。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class M7EntrySwitchVerificationProbeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var appContext: Application
    private lateinit var mockServer: MockWebServer
    private lateinit var dispatcher: OfflineForumDispatcher
    private val gbk: Charset = Charset.forName("GBK")
    private var activeController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(appContext)
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
        mockServer = MockWebServer()
        dispatcher = OfflineForumDispatcher(gbk)
        mockServer.dispatcher = dispatcher
        mockServer.start()

        val prefsFile = SettingsModule.settingsDataStoreFile(appContext)
        prefsFile.parentFile?.mkdirs()
        val seededPrefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FORUM_SERVER to mockServer.url("/").toString().trimEnd('/'),
        )
        FileOutputStream(prefsFile).use { fos ->
            fos.sink().buffer().use { sink ->
                runBlocking { PreferencesSerializer.writeTo(seededPrefs, sink) }
            }
        }
    }

    @After
    fun tearDown() {
        activeController?.let { controller ->
            runCatching { controller.pause().stop().destroy() }
        }
        activeController = null
        mockServer.close()
    }

    // region Probe 1: 清单、入口、别名、快捷方式与 3 类通知

    @Test
    fun probe1_manifestEntryPointsAliasesLegacyIsolationAndPackageName() {
        assertEquals("net.jejer.hipda.next", appContext.packageName)

        val pm = appContext.packageManager
        val pkgInfo = pm.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val activitiesByName = pkgInfo.activities.orEmpty().associateBy { it.name }

        // 1. 正式主入口 MainActivity 属性与 LAUNCHER 解析
        val mainInfo = activitiesByName["net.jejer.hipda.feature.app.MainActivity"]
            ?: error("MainActivity missing from merged manifest")
        assertTrue("MainActivity must be exported", mainInfo.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, mainInfo.launchMode)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            mainInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        assertEquals(R.xml.shortcuts, mainInfo.metaData?.getInt("android.app.shortcuts"))

        val launcherResolved = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).filter { it.activityInfo?.packageName == appContext.packageName }
            .map { it.activityInfo.name }
        assertTrue("LAUNCHER must include MainActivity", "net.jejer.hipda.feature.app.MainActivity" in launcherResolved)
        assertFalse("Old MainFrameActivity must not be LAUNCHER", "net.jejer.hipda.ui.MainFrameActivity" in launcherResolved)
        assertFalse("Legacy NextAppActivity must be removed", "net.jejer.hipda.debug.next.NextAppActivity" in launcherResolved)
        assertFalse("Legacy NextAppActivity must not exist in manifest", "net.jejer.hipda.debug.next.NextAppActivity" in activitiesByName)

        // 2. 所有旧版 Activity 均非 exported 且不响应 LAUNCHER / 4d4y.com VIEW
        val legacyActivities = listOf(
            "net.jejer.hipda.ui.MainFrameActivity",
            "net.jejer.hipda.ui.IntentActivity",
            "net.jejer.hipda.ui.ImageViewerActivity",
            "net.jejer.hipda.ui.PostActivity",
            "net.jejer.hipda.ui.ThreadDetailActivity",
            "net.jejer.hipda.ui.UserInfoActivity",
            "net.jejer.hipda.ui.SettingActivity",
            "net.jejer.hipda.ui.SmsActivity",
            "net.jejer.hipda.ui.SimpleListActivity",
            "net.jejer.hipda.ui.challenge.ChallengeActivity",
        )
        for (name in legacyActivities) {
            val info = activitiesByName[name] ?: error("Expected activity $name in manifest")
            assertFalse("Legacy/internal activity $name must not be exported", info.exported)
        }

        // 3. 保留 .ng.IntentActivity 别名并覆盖 5 条 4d4y.com pathPrefix
        val aliasInfo = activitiesByName["net.jejer.hipda.ng.IntentActivity"]
            ?: error(".ng.IntentActivity alias missing from merged manifest")
        assertTrue(".ng.IntentActivity alias must be exported", aliasInfo.exported)
        assertEquals("net.jejer.hipda.feature.app.MainActivity", aliasInfo.targetActivity)

        val deepLinkUrls = listOf(
            "https://www.4d4y.com/forum/forumdisplay.php?fid=2",
            "https://www.4d4y.com/forum/viewthread.php?tid=12345",
            "https://www.4d4y.com/forum/gotopost.php?pid=67890",
            "https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=12345",
            "https://www.4d4y.com/forum/space.php?uid=42",
        )
        for (url in deepLinkUrls) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(appContext.packageName)
            val resolvedNames = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.name }
            assertEquals("URL $url must resolve strictly to .ng.IntentActivity", listOf("net.jejer.hipda.ng.IntentActivity"), resolvedNames)
        }

        // 4. 源码主清单静态核查：唯一 LAUNCHER + <queries> 完备 + targetActivity 准确
        val mainManifestDoc = parseXmlFile(locateProjectFile("hipda/src/main/AndroidManifest.xml"))
        val mainLauncherFilters = findMainLauncherActivities(mainManifestDoc)
        assertEquals(listOf(".feature.app.MainActivity"), mainLauncherFilters)
        assertQueriesPresent(mainManifestDoc)
    }

    @Test
    fun probe1_shortcutsXmlAndThreeNotificationTypesTargetMainActivity() {
        val shortcuts = parseShortcutsXml()
        assertEquals(listOf("new_posts", "search", "sms", "favorite"), shortcuts.map { it.id })
        for (s in shortcuts) {
            assertTrue("Shortcut ${s.id} must be enabled", s.enabled)
            assertEquals("net.jejer.hipda.next", s.targetPackage)
            assertEquals("net.jejer.hipda.feature.app.MainActivity", s.targetClass)
        }

        val poster = AndroidNotificationPoster(appContext)
        val nm = appContext.getSystemService(NotificationManager::class.java)!!
        val shadowNm = Shadows.shadowOf(nm)

        // 通知类型 1：单人未读短消息（必须直达 uid，严格禁止带 EXTRA_USERNAME）
        ShadowPendingIntent.reset()
        poster.postUnread(
            UnreadSnapshot(
                smsUnreadCount = 1,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "102030",
                    author = "UntrustedAuthor",
                    avatarUrl = null,
                    time = "2026-11-26 12:00",
                    summary = "你好",
                    isNew = true,
                ),
            ),
        )
        val singleSmsIntent = Shadows.shadowOf(shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent
        assertEquals(ComponentName(appContext.packageName, MainActivity::class.java.name), singleSmsIntent.component)
        assertEquals(Constants.INTENT_NOTIFICATION, singleSmsIntent.action)
        assertEquals(1, singleSmsIntent.getIntExtra(Constants.EXTRA_SMS_COUNT, -1))
        assertEquals(0, singleSmsIntent.getIntExtra(Constants.EXTRA_THREAD_COUNT, -1))
        assertEquals("102030", singleSmsIntent.getStringExtra(Constants.EXTRA_UID))
        assertFalse(singleSmsIntent.hasExtra(Constants.EXTRA_USERNAME))
        assertEquals(DeepLinkTarget.SmsConversation("102030"), parseIncomingIntent(singleSmsIntent))

        // 先测原地更新路由语义（Robolectric ShadowPendingIntent.FLAG_UPDATE_CURRENT 会保留旧 key，路由仍必须归口 Sms / Notify）
        poster.postUnread(
            UnreadSnapshot(
                smsUnreadCount = 3,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "102030",
                    author = "UntrustedAuthor",
                    avatarUrl = null,
                    time = "2026-11-26 12:00",
                    summary = "多条",
                    isNew = true,
                ),
            ),
        )
        val updatedMultiIntent = Shadows.shadowOf(shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent
        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(updatedMultiIntent))

        // 通知类型 2（独立 PendingIntent 验证）：多条短消息 / 混合通知（落短消息列表，不带 uid 与 username）
        ShadowPendingIntent.reset()
        poster.postUnread(
            UnreadSnapshot(
                smsUnreadCount = 3,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "102030",
                    author = "UntrustedAuthor",
                    avatarUrl = null,
                    time = "2026-11-26 12:00",
                    summary = "多条",
                    isNew = true,
                ),
            ),
        )
        val multiSmsIntent = Shadows.shadowOf(shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent
        assertEquals(ComponentName(appContext.packageName, MainActivity::class.java.name), multiSmsIntent.component)
        assertEquals(3, multiSmsIntent.getIntExtra(Constants.EXTRA_SMS_COUNT, -1))
        assertEquals(0, multiSmsIntent.getIntExtra(Constants.EXTRA_THREAD_COUNT, -1))
        assertNull(multiSmsIntent.getStringExtra(Constants.EXTRA_UID))
        assertFalse(multiSmsIntent.hasExtra(Constants.EXTRA_USERNAME))
        assertEquals(DeepLinkTarget.Sms, parseIncomingIntent(multiSmsIntent))

        // 通知类型 3：仅帖子提醒（落提醒页，不带 uid 与 username）
        ShadowPendingIntent.reset()
        poster.postUnread(UnreadSnapshot(smsUnreadCount = 0, notifyUnreadCount = 4))
        val notifyOnlyIntent = Shadows.shadowOf(shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent
        assertEquals(ComponentName(appContext.packageName, MainActivity::class.java.name), notifyOnlyIntent.component)
        assertEquals(0, notifyOnlyIntent.getIntExtra(Constants.EXTRA_SMS_COUNT, -1))
        assertEquals(4, notifyOnlyIntent.getIntExtra(Constants.EXTRA_THREAD_COUNT, -1))
        assertNull(notifyOnlyIntent.getStringExtra(Constants.EXTRA_UID))
        assertFalse(notifyOnlyIntent.hasExtra(Constants.EXTRA_USERNAME))
        assertEquals(DeepLinkTarget.Notify, parseIncomingIntent(notifyOnlyIntent))
    }

    // endregion

    // region Probe 2: 真实 MainActivity 冷/热启动、旋转、最近任务恢复、外链与安全矩阵

    @Test
    fun probe2_allFourShortcutsLandCorrectlyViaMainActivityColdAndHotStart() {
        val shortcuts = parseShortcutsXml()
        val first = shortcuts.first { it.id == "new_posts" }
        val (controller, vm) = launchMainActivity(first.toLaunchIntent())
        composeRule.waitUntil(5_000) { vm.state.value.activeForumSource == ForumListSource.TodayNew }
        assertEquals(ShellDestination.Forum, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.TodayNew, vm.state.value.selectedSidebarEntry)

        // 热启动切换其余 3 个快捷方式
        val search = shortcuts.first { it.id == "search" }
        deliverNewIntent(controller, search.toLaunchIntent())
        composeRule.waitUntil(5_000) { vm.state.value.selectedDestination == ShellDestination.Search }
        assertEquals(ShellSidebarEntry.Search, vm.state.value.selectedSidebarEntry)

        val sms = shortcuts.first { it.id == "sms" }
        deliverNewIntent(controller, sms.toLaunchIntent())
        composeRule.waitUntil(5_000) {
            vm.state.value.selectedDestination == ShellDestination.Messages &&
                vm.state.value.selectedSidebarEntry == ShellSidebarEntry.Sms
        }

        val favorite = shortcuts.first { it.id == "favorite" }
        deliverNewIntent(controller, favorite.toLaunchIntent())
        composeRule.waitUntil(5_000) {
            vm.state.value.selectedDestination == ShellDestination.Mine &&
                vm.state.value.selectedSidebarEntry == ShellSidebarEntry.FavoritesAndAttention &&
                vm.state.value.activeMineSubPage == ShellSidebarEntry.FavoritesAndAttention
        }
    }

    @Test
    fun probe2_allThreeNotificationTypesLandCorrectlyAndDropUntrustedUsername() {
        // 即使伪造传入 EXTRA_USERNAME，MainActivity 也必须丢弃且仅按 uid 打开会话
        val singleSmsWithSpoofedName = MainActivity.createNotificationIntent(
            context = appContext,
            smsCount = 1,
            threadCount = 0,
            uid = "102030",
        ).apply {
            putExtra(Constants.EXTRA_USERNAME, "SpoofedUsername")
        }
        val (controller, vm) = launchMainActivity(singleSmsWithSpoofedName)
        composeRule.waitUntil(5_000) { vm.state.value.selectedSmsConversation != null }
        assertEquals(ShellDestination.Messages, vm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Sms, vm.state.value.selectedSidebarEntry)
        assertEquals(SmsConversationArgs(uid = "102030", username = ""), vm.state.value.selectedSmsConversation)

        // 多条/混合未读 -> 短消息列表，无单聊会话
        val multiSms = MainActivity.createNotificationIntent(appContext, smsCount = 2, threadCount = 1)
        deliverNewIntent(controller, multiSms)
        composeRule.waitUntil(5_000) {
            vm.state.value.selectedDestination == ShellDestination.Messages &&
                vm.state.value.selectedSidebarEntry == ShellSidebarEntry.Sms &&
                vm.state.value.selectedSmsConversation == null
        }

        // 仅帖子提醒 -> 提醒列表
        val notifyOnly = MainActivity.createNotificationIntent(appContext, smsCount = 0, threadCount = 5)
        deliverNewIntent(controller, notifyOnly)
        composeRule.waitUntil(5_000) {
            vm.state.value.selectedDestination == ShellDestination.Messages &&
                vm.state.value.selectedSidebarEntry == ShellSidebarEntry.Notify &&
                vm.state.value.selectedSmsConversation == null
        }

        // 边界：单条短消息但 uid 为空 -> 安全回退短消息列表，不打开空 uid 会话
        val singleSmsBlankUid = MainActivity.createNotificationIntent(appContext, smsCount = 1, threadCount = 0, uid = "")
        deliverNewIntent(controller, singleSmsBlankUid)
        composeRule.waitUntil(5_000) {
            vm.state.value.selectedDestination == ShellDestination.Messages &&
                vm.state.value.selectedSidebarEntry == ShellSidebarEntry.Sms &&
                vm.state.value.selectedSmsConversation == null
        }
    }

    @Test
    fun probe2_fiveDeepLinkPrefixesAndUnsupportedUrlLandViaMainActivity() {
        // 预先向 ShadowPackageManager 注册一个仅声明 ACTION_VIEW + BROWSABLE + https 的第三方浏览器，
        // 以便验证点击不支持深链 Snackbar「在浏览器中打开」时在真实 MainActivity 链路准确拉起外部浏览器且零自环。
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val externalBrowser = ComponentName("com.vendor.edgebrowser", "com.vendor.edgebrowser.Main")
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = externalBrowser.packageName
                name = externalBrowser.className
                exported = true
            },
        )
        shadowPm.addIntentFilterForActivity(
            externalBrowser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )

        val (controller, vm) = launchMainActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/forumdisplay.php?fid=7")),
        )
        waitUntilCondition { vm.state.value.activeForumSource == ForumListSource.Forum(7) }

        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=88888")))
        waitUntilCondition { vm.state.value.selectedThread?.tid == "88888" }

        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/gotopost.php?pid=555666")))
        waitUntilCondition {
            vm.state.value.selectedThread?.tid == "88888" && vm.state.value.selectedThread?.targetPid == "555666"
        }

        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=77777")))
        waitUntilCondition {
            vm.state.value.selectedThread?.tid == "77777" && vm.state.value.selectedThread?.targetPid == "555777"
        }

        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/space.php?uid=12345")))
        waitUntilCondition {
            vm.state.value.detailDrilldownStack == listOf(DetailDrilldownEntry.UserProfile("12345", ""))
        }

        // 命中 .ng.IntentActivity pathPrefix 但不支持解析的 URL：弹窗提示且不自循环发 Intent
        val activity = controller.get()
        val shadowActivity = Shadows.shadowOf(activity)
        val unsupportedUrl = "https://www.4d4y.com/forum/space.php?username=edgecase"
        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse(unsupportedUrl)))
        waitUntilCondition {
            composeRule.onAllNodesWithText(appContext.getString(R.string.routing_deeplink_unsupported_url))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(appContext.getString(R.string.routing_action_open_in_browser))
            .assertIsDisplayed()
        assertNull("Unsupported deep link must not auto-start external VIEW intent", shadowActivity.nextStartedActivity)

        // 点击「在浏览器中打开」：准确拉起第三方浏览器，绝不拉起自身
        composeRule.onNodeWithText(appContext.getString(R.string.routing_action_open_in_browser)).performClick()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val launched = shadowActivity.nextStartedActivity
        assertNotNull("Clicking Open in browser must launch external browser", launched)
        assertEquals(externalBrowser, launched!!.component)
        assertEquals(unsupportedUrl, launched.dataString)
    }

    @Test
    fun probe2_rotationRecreateDoesNotReplayColdOrWarmIntent() {
        val searchIntent = Intent(Constants.INTENT_SEARCH).setClass(appContext, MainActivity::class.java)
        val (controller, vm) = launchMainActivity(searchIntent)
        composeRule.waitUntil(5_000) { vm.state.value.selectedDestination == ShellDestination.Search }

        // 用户切到版块 7 后发生配置变更旋转（savedInstanceState != null，activity.intent 仍为 search）
        composeRule.runOnUiThread { vm.selectForumSource(ForumListSource.Forum(7)) }
        composeRule.waitUntil(5_000) { vm.state.value.activeForumSource == ForumListSource.Forum(7) }

        controller.recreate()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val vmAfterFirstRotate = ViewModelProvider(controller.get())[NextAppHostViewModel::class.java]
        assertEquals(ShellDestination.Forum, vmAfterFirstRotate.state.value.selectedDestination)
        assertEquals(ForumListSource.Forum(7), vmAfterFirstRotate.state.value.activeForumSource)

        // 热启动收到新帖子深链 -> 关闭帖子 -> 再次旋转不得重放帖子深链
        deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=88888")))
        composeRule.waitUntil(5_000) { vmAfterFirstRotate.state.value.selectedThread?.tid == "88888" }
        composeRule.runOnUiThread { vmAfterFirstRotate.closeThread() }
        composeRule.waitUntil(5_000) { vmAfterFirstRotate.state.value.selectedThread == null }

        controller.recreate()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val vmAfterSecondRotate = ViewModelProvider(controller.get())[NextAppHostViewModel::class.java]
        assertNull("Thread deep link must not replay after recreate()", vmAfterSecondRotate.state.value.selectedThread)
        assertEquals(ForumListSource.Forum(7), vmAfterSecondRotate.state.value.activeForumSource)
    }

    @Test
    fun probe2_launchedFromHistoryDoesNotReplayShortcutNotificationOrDeepLink() {
        val historyShortcutIntent = Intent(Constants.INTENT_SMS)
            .setClass(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val (_, vm) = launchMainActivity(historyShortcutIntent)
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Forum, vm.state.value.selectedDestination)
        assertEquals(ForumListSource.Forum(NextAppState.DEFAULT_FORUM_FID), vm.state.value.activeForumSource)
        assertNull(vm.state.value.selectedThread)
    }

    @Test
    fun probe2_launchedFromHistoryDeepLinkDoesNotReplay() {
        val historyViewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=88888"))
            .setClass(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val (_, vm) = launchMainActivity(historyViewIntent)
        composeRule.waitForIdle()
        assertNull(vm.state.value.selectedThread)
        assertEquals(ShellDestination.Forum, vm.state.value.selectedDestination)
    }

    @Test
    fun probe2_nonNullSavedInstanceStateOnCreateDoesNotReplayIntent() {
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=88888"))
            .setClass(appContext, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, deepLinkIntent)
            .create(Bundle().apply { putBoolean("probe_restored", true) })
            .start()
            .resume()
            .visible()
        activeController = controller
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val vm = ViewModelProvider(controller.get())[NextAppHostViewModel::class.java]
        assertNull("onCreate with non-null savedInstanceState must not replay Intent", vm.state.value.selectedThread)
        assertEquals(ShellDestination.Forum, vm.state.value.selectedDestination)
    }

    @Test
    fun probe2_dangerousSchemeMatrixAndBadParcelableAdversaryCauseZeroNavigationAndZeroOutboundStarts() {
        val (controller, vm) = launchMainActivity(Intent(Intent.ACTION_MAIN))
        // 等待首屏版块异步加载完全收敛后再记录请求基线，避免启动期后台 forumdisplay 请求在循环中途落网
        waitUntilCondition {
            vm.state.value.activeForumSource == ForumListSource.Forum(2) &&
                composeRule.onAllNodesWithText("离线测试帖").fetchSemanticsNodes().isNotEmpty()
        }
        val shadowActivity = Shadows.shadowOf(controller.get())
        val baselineRequests = dispatcher.recordedPaths.size

        val dangerousPayloads = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:alert(document.cookie)",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "file:///etc/passwd",
            "content://settings/secure",
            "data:text/html,<script>alert(1)</script>",
            "tel:10086",
            "vbscript:msgbox(1)",
            "blob:https://www.4d4y.com/550e8400-e29b-41d4-a716-446655440000",
            "about:blank",
            "ftp://ftp.4d4y.com/archive.zip",
            "sms:10086",
            "market://details?id=net.jejer.hipda.next",
            "android.resource://net.jejer.hipda.next/raw/test",
            "mailto:attacker@example.com",
            "https://user:pass@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://www.4d4y.com%40evil.com/forum/viewthread.php?tid=1",
            "//www.4d4y.com/forum/viewthread.php?tid=1",
            "https://www.4d4y.com/\u200Bforum/viewthread.php?tid=1",
            "https://www.4d4y.com/forum/viewthread.php?tid=1\r\n",
        )

        val snapshotBefore = vm.state.value
        for (payload in dangerousPayloads) {
            deliverNewIntent(controller, Intent(Intent.ACTION_VIEW, Uri.parse(payload)))
            deliverNewIntent(controller, Intent(Intent.ACTION_VIEW).putExtra(EXTRA_DEEP_LINK_URL, payload))
            assertEquals("Dangerous payload must not mutate state: $payload", snapshotBefore, vm.state.value)
            assertNull("Dangerous payload must not start external activity: $payload", shadowActivity.nextStartedActivity)
        }
        assertEquals(
            "Dangerous scheme matrix must trigger zero extra HTTP requests (got: ${dispatcher.recordedPaths})",
            baselineRequests,
            dispatcher.recordedPaths.size,
        )

        // BadParcelableException 对抗：外部 Intent 携带非法类 Parcelable Extra 时不崩且仍保留合法 data URI
        val corruptedViewIntent = buildIntentWithCorruptedParcelableExtra(
            action = Intent.ACTION_VIEW,
            data = Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=88888"),
        )
        deliverNewIntent(controller, corruptedViewIntent)
        waitUntilCondition { vm.state.value.selectedThread?.tid == "88888" }

        // 即使直接对 sanitizeIncomingIntent 传入 BadParcelable Intent 也必须安全脱敏
        val corruptedExtraOnly = buildIntentWithCorruptedParcelableExtra(
            action = Constants.INTENT_NOTIFICATION,
            data = null,
        )
        val sanitized = MainActivity.sanitizeIncomingIntent(corruptedExtraOnly)
        assertNotNull(sanitized)
        assertEquals(Constants.INTENT_NOTIFICATION, sanitized!!.action)
    }

    // endregion

    // region Helpers

    private fun launchMainActivity(intent: Intent): Pair<ActivityController<MainActivity>, NextAppHostViewModel> {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        activeController = controller
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val vm = ViewModelProvider(controller.get())[NextAppHostViewModel::class.java]
        return controller to vm
    }

    private fun waitUntilCondition(timeoutMillis: Long = 15_000L, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun deliverNewIntent(controller: ActivityController<MainActivity>, intent: Intent) {
        controller.newIntent(intent)
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    private class UnresolvableCustomParcelable : Parcelable {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<UnresolvableCustomParcelable> =
                object : Parcelable.Creator<UnresolvableCustomParcelable> {
                    override fun createFromParcel(source: Parcel): UnresolvableCustomParcelable =
                        UnresolvableCustomParcelable()

                    override fun newArray(size: Int): Array<UnresolvableCustomParcelable?> = arrayOfNulls(size)
                }
        }
    }

    private fun buildIntentWithCorruptedParcelableExtra(action: String, data: Uri?): Intent {
        val sourceBundle = Bundle().apply {
            putParcelable("evil_extra", UnresolvableCustomParcelable())
        }
        val parcel = Parcel.obtain()
        val unparceledWithBootLoader = try {
            parcel.writeBundle(sourceBundle)
            parcel.setDataPosition(0)
            // 使用仅含引导类加载器的 Bundle，在首次 getStringExtra() 解包时触发真实 BadParcelableException
            parcel.readBundle(String::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
        return Intent(action).apply {
            if (data != null) this.data = data
            replaceExtras(unparceledWithBootLoader)
        }
    }

    private data class ShortcutSpec(
        val id: String,
        val enabled: Boolean,
        val action: String,
        val targetPackage: String,
        val targetClass: String,
    ) {
        fun toLaunchIntent(): Intent = Intent(action).apply {
            component = ComponentName(targetPackage, targetClass)
        }
    }

    private fun parseShortcutsXml(): List<ShortcutSpec> {
        val ns = "http://schemas.android.com/apk/res/android"
        val parser: XmlResourceParser = appContext.resources.getXml(R.xml.shortcuts)
        val items = mutableListOf<ShortcutSpec>()
        var currentId: String? = null
        var currentEnabled = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        currentId = parser.getAttributeValue(ns, "shortcutId")
                        currentEnabled = parser.getAttributeBooleanValue(ns, "enabled", false)
                    }
                    "intent" -> {
                        val id = currentId
                        if (id != null) {
                            items += ShortcutSpec(
                                id = id,
                                enabled = currentEnabled,
                                action = parser.getAttributeValue(ns, "action").orEmpty(),
                                targetPackage = parser.getAttributeValue(ns, "targetPackage").orEmpty(),
                                targetClass = parser.getAttributeValue(ns, "targetClass").orEmpty(),
                            )
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "shortcut") {
                currentId = null
            }
            event = parser.next()
        }
        parser.close()
        return items
    }

    private fun locateProjectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("Cannot locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun parseXmlFile(file: File): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun findMainLauncherActivities(doc: org.w3c.dom.Document): List<String> {
        val ns = "http://schemas.android.com/apk/res/android"
        val result = mutableListOf<String>()
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            val filters = el.getElementsByTagName("intent-filter")
            var hasLauncher = false
            for (j in 0 until filters.length) {
                val filter = filters.item(j) as Element
                val hasMain = (0 until filter.getElementsByTagName("action").length).any { idx ->
                    (filter.getElementsByTagName("action").item(idx) as Element).getAttributeNS(ns, "name") == Intent.ACTION_MAIN
                }
                val hasCat = (0 until filter.getElementsByTagName("category").length).any { idx ->
                    (filter.getElementsByTagName("category").item(idx) as Element).getAttributeNS(ns, "name") == Intent.CATEGORY_LAUNCHER
                }
                if (hasMain && hasCat) hasLauncher = true
            }
            if (hasLauncher) result += el.getAttributeNS(ns, "name")
        }
        return result
    }

    private fun assertQueriesPresent(doc: org.w3c.dom.Document) {
        val ns = "http://schemas.android.com/apk/res/android"
        val queriesNodes = doc.getElementsByTagName("queries")
        assertTrue("Manifest must declare <queries>", queriesNodes.length == 1)
        val queriesEl = queriesNodes.item(0) as Element
        val intents = queriesEl.getElementsByTagName("intent")
        val viewSchemes = mutableSetOf<String>()
        var hasAppBrowser = false
        for (i in 0 until intents.length) {
            val intentEl = intents.item(i) as Element
            val actions = (0 until intentEl.getElementsByTagName("action").length).map {
                (intentEl.getElementsByTagName("action").item(it) as Element).getAttributeNS(ns, "name")
            }
            val categories = (0 until intentEl.getElementsByTagName("category").length).map {
                (intentEl.getElementsByTagName("category").item(it) as Element).getAttributeNS(ns, "name")
            }
            val schemes = (0 until intentEl.getElementsByTagName("data").length).map {
                (intentEl.getElementsByTagName("data").item(it) as Element).getAttributeNS(ns, "scheme")
            }
            if (Intent.ACTION_VIEW in actions && Intent.CATEGORY_BROWSABLE in categories) {
                viewSchemes += schemes
            }
            if (Intent.ACTION_MAIN in actions && Intent.CATEGORY_APP_BROWSER in categories) {
                hasAppBrowser = true
            }
        }
        assertTrue("<queries> must cover http/https/mailto", viewSchemes.containsAll(setOf("http", "https", "mailto")))
        assertTrue("<queries> must include MAIN + APP_BROWSER", hasAppBrowser)
    }

    private class OfflineForumDispatcher(private val gbk: Charset) : Dispatcher() {
        val recordedPaths = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val query = request.url.encodedQuery.orEmpty()
            recordedPaths += "$path?$query"
            val html = when {
                path.endsWith("gotopost.php") -> threadHtml(tid = "88888", pid = "555666", page = 1)
                path.endsWith("redirect.php") && query.contains("goto=lastpost") -> threadHtml(tid = "77777", pid = "555777", page = 1)
                path.endsWith("viewthread.php") -> {
                    val tid = request.url.queryParameter("tid") ?: "88888"
                    val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                    threadHtml(tid = tid, pid = if (page > 1) "555667" else "555666", page = page)
                }
                else -> forumHtml()
            }
            val bytes = Buffer().write(html.toByteArray(gbk))
            return MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=gbk")
                .body(bytes)
                .build()
        }

        private fun forumHtml(): String = """
            <html><body>
            <div id="umenu"></div>
            <table id="forum_2"><tbody id="normalthread_100">
                <td class="folder"></td><td class="icon"></td>
                <th class="subject"><span><a href="viewthread.php?tid=100">离线测试帖</a></span></th>
                <td class="author"><cite><a href="space.php?uid=1">tester</a></cite><em>2026-11-26</em></td>
                <td class="nums"><strong>1</strong><em>10</em></td>
                <td class="lastpost"><em><a href="redirect.php?tid=100">2026-11-26</a></em><cite>tester</cite></td>
            </tbody></table>
            </body></html>
        """.trimIndent()

        private fun threadHtml(tid: String, pid: String, page: Int): String = """
            <html><body>
            <div id="nav"><a href="forumdisplay.php?fid=2">Discovery</a> &raquo; 离线主题 $tid</div>
            <div id="wrap">
                <input type="hidden" name="tid" value="$tid" />
                <div class="forumcontrol"><div class="pages"><strong>$page</strong></div></div>
                <div id="postlist">
                    <div id="post_$pid">
                        <table><tbody><tr>
                            <td class="postauthor"><div class="postinfo"><a href="space.php?uid=42">tester</a></div></td>
                            <td class="postcontent">
                                <div class="postinfo">
                                    <strong><a><em>1</em></a></strong>
                                    <div class="posterinfo"><div class="authorinfo"><em>2026-11-26 12:00</em></div></div>
                                </div>
                                <div class="t_msgfontfix"><div id="postmessage_$pid" class="t_msgfont">离线正文</div></div>
                            </td>
                        </tr></tbody></table>
                    </div>
                </div>
            </div>
            </body></html>
        """.trimIndent()
    }

    // endregion
}
