package net.jejer.hipda.feature.app

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.jejer.hipda.R
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.AppIntentConstants
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.routing.AndroidExternalLinkOpener
import net.jejer.hipda.feature.routing.EXTRA_DEEP_LINK_URL
import net.jejer.hipda.feature.routing.SafeLinkAndRoutingSecurityTest
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.service.AndroidNotificationPoster
import net.jejer.hipda.ui.designsystem.PreviewRootContent
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser

/**
 * M7-1 正式主入口、`.ng.IntentActivity` 别名、`<queries>`、桌面快捷方式、通知 PendingIntent、
 * 包名/签名零变更、危险 scheme 阻断与 Debug 预览去重集成验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MainActivityEntryAndManifestTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun packageNameNamespaceAndSigning_remainUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("applicationId 必须保持 net.jejer.hipda.next", "net.jejer.hipda.next", context.packageName)

        val buildGradle = File(resolveHipdaModuleDir(), "build.gradle.kts").readText()
        assertTrue(
            "namespace 必须保持 net.jejer.hipda",
            Regex("""namespace\s*=\s*"net\.jejer\.hipda"""").containsMatchIn(buildGradle),
        )
        assertTrue(
            "applicationId 必须保持 net.jejer.hipda.next",
            Regex("""applicationId\s*=\s*"net\.jejer\.hipda\.next"""").containsMatchIn(buildGradle),
        )
        assertFalse("严禁新增或修改签名配置", buildGradle.contains("signingConfigs"))
        assertFalse("严禁新增或修改 signingConfig", buildGradle.contains("signingConfig"))
    }

    @Test
    fun manifest_switchesLauncherAndAliasToMainActivity_andDeclaresR16Queries() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager

        // 1. LAUNCHER 必须指向新版 MainActivity，旧 MainFrameActivity 与冗余 NextAppActivity 不在 LAUNCHER 中
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val appLaunchers = pm.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName == context.packageName }
            .map { it.activityInfo.name }
            .toSet()
        assertTrue(
            "LAUNCHER 必须包含正式主入口 ${MainActivity::class.java.name}，实际：$appLaunchers",
            MainActivity::class.java.name in appLaunchers,
        )
        assertFalse(
            "旧 MainFrameActivity 必须已移出 LAUNCHER",
            "net.jejer.hipda.ui.MainFrameActivity" in appLaunchers,
        )
        assertFalse(
            "debug 冗余 NextAppActivity 必须已清理",
            "net.jejer.hipda.debug.next.NextAppActivity" in appLaunchers,
        )

        @Suppress("DEPRECATION")
        val mainActivityInfo = pm.getActivityInfo(
            ComponentName(context.packageName, MainActivity::class.java.name),
            PackageManager.GET_META_DATA,
        )
        assertTrue("MainActivity 必须 exported=true", mainActivityInfo.exported)
        assertEquals("MainActivity 必须为 singleTop", ActivityInfo.LAUNCH_SINGLE_TOP, mainActivityInfo.launchMode)
        @Suppress("DEPRECATION")
        val adjustResizeFlag = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertEquals(
            "MainActivity 必须声明 adjustResize",
            adjustResizeFlag,
            mainActivityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        assertEquals(
            "MainActivity 必须挂载 @xml/shortcuts",
            R.xml.shortcuts,
            mainActivityInfo.metaData?.getInt("android.app.shortcuts"),
        )

        val removedLegacyActivities = listOf(
            "net.jejer.hipda.ui.MainFrameActivity",
            "net.jejer.hipda.ui.IntentActivity",
            "net.jejer.hipda.ui.ImageViewerActivity",
            "net.jejer.hipda.ui.PostActivity",
            "net.jejer.hipda.ui.ThreadDetailActivity",
            "net.jejer.hipda.ui.UserInfoActivity",
            "net.jejer.hipda.ui.SettingActivity",
            "net.jejer.hipda.ui.SmsActivity",
            "net.jejer.hipda.ui.SimpleListActivity",
        )
        for (legacyClassName in removedLegacyActivities) {
            val lookupError = runCatching {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(ComponentName(context.packageName, legacyClassName), 0)
            }.exceptionOrNull()
            assertTrue(
                "旧 Activity $legacyClassName 必须已从 AndroidManifest.xml 删除，实际结果：$lookupError",
                lookupError is PackageManager.NameNotFoundException,
            )
        }

        // 2. .ng.IntentActivity 别名必须保留、指向 MainActivity，并准确解析 5 条 4d4y.com pathPrefix
        val fiveDeepLinkUrls = listOf(
            "https://www.4d4y.com/forum/forumdisplay.php?fid=2",
            "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
            "https://www.4d4y.com/forum/gotopost.php?pid=74880921",
            "https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=3468951",
            "https://www.4d4y.com/forum/space.php?uid=10000",
        )
        for (url in fiveDeepLinkUrls) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            @Suppress("DEPRECATION")
            val selfMatches = pm.queryIntentActivities(viewIntent, 0)
                .filter { it.activityInfo.packageName == context.packageName }
            assertEquals("每条 4d4y.com 链接应精确命中 .ng.IntentActivity：$url", 1, selfMatches.size)
            val info = selfMatches.single().activityInfo
            assertEquals("net.jejer.hipda.ng.IntentActivity", info.name)
            assertTrue(info.exported)
            assertEquals(MainActivity::class.java.name, info.targetActivity)
        }

        // 3. 解析 src/main/AndroidManifest.xml 源码确认 <activity-alias> 与 <queries> 完整
        val moduleDir = resolveHipdaModuleDir()
        val mainDoc = parseXml(File(moduleDir, "src/main/AndroidManifest.xml"))

        val aliases = mainDoc.getElementsByTagName("activity-alias")
        val ngAlias = (0 until aliases.length)
            .map { aliases.item(it) as Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == ".ng.IntentActivity" }
        assertNotNull("主清单必须保留 <activity-alias android:name=\".ng.IntentActivity\">", ngAlias)
        assertEquals("true", ngAlias!!.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals(".feature.app.MainActivity", ngAlias.getAttributeNS(ANDROID_NS, "targetActivity"))

        val dataNodes = ngAlias.getElementsByTagName("data")
        val schemes = mutableSetOf<String>()
        val hosts = mutableSetOf<String>()
        val prefixes = mutableSetOf<String>()
        for (i in 0 until dataNodes.length) {
            val el = dataNodes.item(i) as Element
            el.getAttributeNS(ANDROID_NS, "scheme").takeIf { it.isNotEmpty() }?.let(schemes::add)
            el.getAttributeNS(ANDROID_NS, "host").takeIf { it.isNotEmpty() }?.let(hosts::add)
            el.getAttributeNS(ANDROID_NS, "pathPrefix").takeIf { it.isNotEmpty() }?.let(prefixes::add)
        }
        assertEquals(setOf("https"), schemes)
        assertEquals(setOf("www.4d4y.com"), hosts)
        assertEquals(
            setOf(
                "/forum/forumdisplay.php",
                "/forum/viewthread.php",
                "/forum/gotopost.php",
                "/forum/redirect.php",
                "/forum/space.php",
            ),
            prefixes,
        )

        val queriesList = mainDoc.getElementsByTagName("queries")
        assertTrue("主清单必须声明 <queries> 解决 Android 11+ 浏览器包可见性", queriesList.length >= 1)
        val querySignatures = mutableSetOf<String>()
        for (qIdx in 0 until queriesList.length) {
            val qEl = queriesList.item(qIdx) as Element
            val intents = qEl.getElementsByTagName("intent")
            for (iIdx in 0 until intents.length) {
                val intentEl = intents.item(iIdx) as Element
                val action = firstAttr(intentEl, "action", "name")
                val category = firstAttr(intentEl, "category", "name")
                val scheme = firstAttr(intentEl, "data", "scheme")
                querySignatures += "$action|$category|$scheme"
            }
        }
        assertTrue("缺少 http BROWSABLE <queries>", "android.intent.action.VIEW|android.intent.category.BROWSABLE|http" in querySignatures)
        assertTrue("缺少 https BROWSABLE <queries>", "android.intent.action.VIEW|android.intent.category.BROWSABLE|https" in querySignatures)
        assertTrue("缺少 mailto BROWSABLE <queries>", "android.intent.action.VIEW|android.intent.category.BROWSABLE|mailto" in querySignatures)
        assertTrue("缺少 APP_BROWSER <queries>", "android.intent.action.MAIN|android.intent.category.APP_BROWSER|" in querySignatures)

        // 4. src/debug/AndroidManifest.xml 保留 PreviewActivity 且移除冗余 NextAppActivity
        val debugDoc = parseXml(File(moduleDir, "src/debug/AndroidManifest.xml"))
        val debugActivities = debugDoc.getElementsByTagName("activity")
        val debugActivityNames = (0 until debugActivities.length)
            .map { (debugActivities.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            .toSet()
        assertTrue("debug 清单应保留 PreviewActivity", ".ui.designsystem.PreviewActivity" in debugActivityNames)
        assertFalse("debug 清单不应残留冗余 NextAppActivity", ".debug.next.NextAppActivity" in debugActivityNames)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun shortcutsXml_allFourShortcutsTargetMainActivityWithPreservedPackage_andRouteEndToEndWithoutReplayOnRotation() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shortcuts = parseShortcutsXml(context)

        assertEquals(listOf("new_posts", "search", "sms", "favorite"), shortcuts.map { it.id })
        shortcuts.forEach { shortcut ->
            assertEquals("快捷方式 targetPackage 必须保持 net.jejer.hipda.next", "net.jejer.hipda.next", shortcut.targetPackage)
            assertEquals("快捷方式 targetClass 必须指向新版 MainActivity", MainActivity::class.java.name, shortcut.targetClass)
        }

        // 以第一个快捷方式（new_posts）冷启动，再旋转验证不重放，再通过 onNewIntent 依次验证其余 3 个快捷方式及旋转不重放
        val harness = MultiPaneTestHarness()
        MainEntryLifecycleHarnessActivity.activeHarness = harness
        val firstIntent = shortcuts[0].toIntent()
        val controller = Robolectric.buildActivity(MainEntryLifecycleHarnessActivity::class.java, firstIntent).setup()
        try {
            val activityBefore = controller.get()
            pumpFrames()
            val host = ViewModelProvider(activityBefore)[NextAppHostViewModel::class.java]
            assertEquals(ForumListSource.TodayNew, host.state.value.activeForumSource)
            assertEquals(ShellSidebarEntry.TodayNew, host.state.value.selectedSidebarEntry)

            // 用户切到版块 6 后旋转 -> 不得重放 new_posts
            host.selectForumSource(ForumListSource.Forum(6))
            pumpFrames()
            val landscape = Configuration(activityBefore.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 749
                screenHeightDp = 559
            }
            controller.configurationChange(landscape)
            val activityAfter = controller.get()
            if (!activityAfter.window.decorView.isAttachedToWindow) controller.visible()
            pumpFrames()
            assertNotSame(activityBefore, activityAfter)
            assertEquals(ForumListSource.Forum(6), host.state.value.activeForumSource)

            // 其余 3 个快捷方式通过 onNewIntent 逐一落地
            controller.newIntent(shortcuts[1].toIntent())
            pumpFrames()
            assertEquals(ShellDestination.Search, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.Search, host.state.value.selectedSidebarEntry)

            controller.newIntent(shortcuts[2].toIntent())
            pumpFrames()
            assertEquals(ShellDestination.Messages, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.Sms, host.state.value.selectedSidebarEntry)

            controller.newIntent(shortcuts[3].toIntent())
            pumpFrames()
            assertEquals(ShellDestination.Mine, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.FavoritesAndAttention, host.state.value.selectedSidebarEntry)

            // 切回版块 2 再旋转 -> 不得重放 favorite
            host.selectForumSource(ForumListSource.Forum(2))
            pumpFrames()
            val portrait = Configuration(activityAfter.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 411
                screenHeightDp = 891
            }
            controller.configurationChange(portrait)
            pumpFrames()
            assertEquals(ForumListSource.Forum(2), host.state.value.activeForumSource)
        } finally {
            controller.destroy()
            MainEntryLifecycleHarnessActivity.activeHarness = null
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun notificationPosterPendingIntents_allThreeCategoriesTargetMainActivityAndRouteWithoutReplayOrUsernameLeak() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        val poster = AndroidNotificationPoster(context)

        // 1. 单人短消息通知 PendingIntent
        poster.postUnread(
            UnreadSnapshot(
                smsUnreadCount = 1,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "20000",
                    author = "HiPeer",
                    avatarUrl = null,
                    time = "刚刚",
                    summary = "晚上见",
                    isNew = true,
                ),
            ),
        )
        val singleSmsIntent = Intent(shadowOf(shadowOf(nm).getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent)
        assertEquals(MainActivity::class.java.name, singleSmsIntent.component?.className)
        assertNull("单人短消息 PendingIntent 不应写入原始用户名", singleSmsIntent.getStringExtra(AppIntentConstants.EXTRA_USERNAME))

        // 2. 多条短消息通知 PendingIntent
        poster.postUnread(UnreadSnapshot(smsUnreadCount = 3, notifyUnreadCount = 0))
        val multiSmsIntent = Intent(shadowOf(shadowOf(nm).getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent)
        assertEquals(MainActivity::class.java.name, multiSmsIntent.component?.className)

        // 3. 仅提醒通知 PendingIntent
        poster.postUnread(UnreadSnapshot(smsUnreadCount = 0, notifyUnreadCount = 4))
        val notifyOnlyIntent = Intent(shadowOf(shadowOf(nm).getNotification(AndroidNotificationPoster.NOTIFICATION_ID).contentIntent).savedIntent)
        assertEquals(MainActivity::class.java.name, notifyOnlyIntent.component?.className)

        // 4. 即使外部伪造携带 EXTRA_USERNAME，经 MainActivity.sanitizeIncomingIntent 仍会被剥离
        val spoofedSingleSms = Intent(singleSmsIntent).putExtra(AppIntentConstants.EXTRA_USERNAME, "UNTRUSTED_PII_NAME")
        val sanitizedSpoofed = MainActivity.sanitizeIncomingIntent(spoofedSingleSms)
        assertNotNull(sanitizedSpoofed)
        assertNull(sanitizedSpoofed!!.getStringExtra(AppIntentConstants.EXTRA_USERNAME))
        assertEquals("20000", sanitizedSpoofed.getStringExtra(AppIntentConstants.EXTRA_UID))

        // 5. 全链路 Activity 生命周期验证三类通知落地与旋转不重放
        val harness = MultiPaneTestHarness()
        MainEntryLifecycleHarnessActivity.activeHarness = harness
        val controller = Robolectric.buildActivity(MainEntryLifecycleHarnessActivity::class.java, spoofedSingleSms).setup()
        try {
            val activityBefore = controller.get()
            pumpFrames()
            val host = ViewModelProvider(activityBefore)[NextAppHostViewModel::class.java]
            assertEquals(ShellDestination.Messages, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.Sms, host.state.value.selectedSidebarEntry)
            assertEquals(SmsConversationArgs("20000", ""), host.state.value.selectedSmsConversation)

            // 关闭对话切版块后旋转 -> 不得重放单条短消息
            host.selectForumSource(ForumListSource.Forum(6))
            pumpFrames()
            val landscape = Configuration(activityBefore.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 749
                screenHeightDp = 559
            }
            controller.configurationChange(landscape)
            val activityAfter = controller.get()
            if (!activityAfter.window.decorView.isAttachedToWindow) controller.visible()
            pumpFrames()
            assertNull(host.state.value.selectedSmsConversation)
            assertEquals(ForumListSource.Forum(6), host.state.value.activeForumSource)

            // 多条短消息通知 via onNewIntent
            controller.newIntent(multiSmsIntent)
            pumpFrames()
            assertEquals(ShellDestination.Messages, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.Sms, host.state.value.selectedSidebarEntry)
            assertNull(host.state.value.selectedSmsConversation)

            // 仅提醒通知 via onNewIntent
            controller.newIntent(notifyOnlyIntent)
            pumpFrames()
            assertEquals(ShellDestination.Messages, host.state.value.selectedDestination)
            assertEquals(ShellSidebarEntry.Notify, host.state.value.selectedSidebarEntry)
        } finally {
            controller.destroy()
            MainEntryLifecycleHarnessActivity.activeHarness = null
        }

        // 6. 从系统最近任务（FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY）重启时不得重放旧通知/快捷方式
        val historyHarness = MultiPaneTestHarness()
        MainEntryLifecycleHarnessActivity.activeHarness = historyHarness
        val fromHistoryIntent = Intent(singleSmsIntent).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val historyController = Robolectric.buildActivity(MainEntryLifecycleHarnessActivity::class.java, fromHistoryIntent).setup()
        try {
            pumpFrames()
            val host = ViewModelProvider(historyController.get())[NextAppHostViewModel::class.java]
            assertNull("从最近任务历史重建不得重放旧通知单聊", host.state.value.selectedSmsConversation)
            assertEquals(ShellDestination.Forum, host.state.value.selectedDestination)
        } finally {
            historyController.destroy()
            MainEntryLifecycleHarnessActivity.activeHarness = null
        }
    }

    @Test
    fun exportedMainActivityAndAlias_blockAllDangerousSchemesAndHostileExtrasWithZeroNavZeroStartActivityZeroCrash() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startedIntents = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(context) { startedIntents += it }
        val host = NextAppHostViewModel()
        val baselineState = host.state.value

        val allBlocked = SafeLinkAndRoutingSecurityTest.ALL_DANGEROUS_URLS + SafeLinkAndRoutingSecurityTest.VALID_MAILTO_URLS
        for (bad in allBlocked) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(bad))
            host.submitIncomingIntent(MainActivity.sanitizeIncomingIntent(viewIntent))
            host.consumePendingIntents(AppSettings.DEFAULT_FORUM_SERVER)
            assertEquals("危险 ACTION_VIEW data 不得改变导航状态 [$bad]", baselineState, host.state.value)

            val extraIntent = Intent(Intent.ACTION_VIEW).putExtra(EXTRA_DEEP_LINK_URL, bad)
            host.submitIncomingIntent(MainActivity.sanitizeIncomingIntent(extraIntent))
            host.consumePendingIntents(AppSettings.DEFAULT_FORUM_SERVER)
            assertEquals("危险 EXTRA_DEEP_LINK_URL 不得改变导航状态 [$bad]", baselineState, host.state.value)

            if (bad !in SafeLinkAndRoutingSecurityTest.VALID_MAILTO_URLS) {
                assertFalse("AndroidExternalLinkOpener 必须拦截 [$bad]", opener.open(bad))
            }
        }
        assertEquals("危险 scheme 必须 0 startActivity", 0, startedIntents.size)

        // 模拟外部应用向导出入口塞入不可反序列化 Parcelable Extra -> 必须 0 崩溃且安全放行合法 data
        val hostileBundle = Bundle().apply {
            putParcelable("evil_extra", ThrowingParcelable())
        }
        val parcel = Parcel.obtain()
        val hostileIntent = try {
            hostileBundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val parceledBundle = Bundle.CREATOR.createFromParcel(parcel)
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=3072")).apply {
                putExtras(parceledBundle)
            }
        } finally {
            parcel.recycle()
        }
        val sanitizedHostile = MainActivity.sanitizeIncomingIntent(hostileIntent)
        assertNotNull("异常 Extra 被吞并后仍应安全返回清理后的 Intent", sanitizedHostile)
        host.submitIncomingIntent(sanitizedHostile)
        host.consumePendingIntents(AppSettings.DEFAULT_FORUM_SERVER)
        assertEquals("3072", host.state.value.selectedThread?.tid)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun previewActivity_retainsDevToolsAndRemovesRedundantNextAppButton() {
        composeRule.setContent {
            PreviewRootContent()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("进入新版").assertDoesNotExist()
        composeRule.onNodeWithText("样本导出").assertIsDisplayed()
        composeRule.onNodeWithText("外壳").assertIsDisplayed()
    }

    private fun pumpFrames() {
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    private data class ParsedShortcut(
        val id: String,
        val action: String,
        val targetPackage: String,
        val targetClass: String,
    ) {
        fun toIntent(): Intent = Intent(action).setClassName(targetPackage, targetClass)
    }

    private fun parseShortcutsXml(context: Application): List<ParsedShortcut> {
        val result = mutableListOf<ParsedShortcut>()
        val parser = context.resources.getXml(R.xml.shortcuts)
        try {
            var currentId: String? = null
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "shortcut" -> currentId = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                        "intent" -> {
                            val id = requireNotNull(currentId)
                            result += ParsedShortcut(
                                id = id,
                                action = requireNotNull(parser.getAttributeValue(ANDROID_NS, "action")),
                                targetPackage = requireNotNull(parser.getAttributeValue(ANDROID_NS, "targetPackage")),
                                targetClass = requireNotNull(parser.getAttributeValue(ANDROID_NS, "targetClass")),
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }
        return result
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun firstAttr(parent: Element, tagName: String, attrName: String): String {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length == 0) return ""
        return (nodes.item(0) as Element).getAttributeNS(ANDROID_NS, attrName)
    }

    private fun resolveHipdaModuleDir(): File {
        val candidates = listOf(File("."), File("hipda"))
        return candidates.firstOrNull { dir ->
            File(dir, "src/main/AndroidManifest.xml").isFile && File(dir, "build.gradle.kts").isFile
        } ?: error("无法定位 hipda 模块目录（当前工作目录：${File(".").absolutePath}）")
    }

    private class ThrowingParcelable : Parcelable {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(1)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<ThrowingParcelable> = object : Parcelable.Creator<ThrowingParcelable> {
                override fun createFromParcel(source: Parcel): ThrowingParcelable {
                    throw BadParcelableException("Simulated hostile external Parcelable")
                }

                override fun newArray(size: Int): Array<ThrowingParcelable?> = arrayOfNulls(size)
            }
        }
    }

    /** 复用正式 [MainActivity.isLaunchedFromHistory] 与 [MainActivity.sanitizeIncomingIntent] 契约的测试宿主。 */
    class MainEntryLifecycleHarnessActivity : ComponentActivity() {
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
            if (savedInstanceState == null && !MainActivity.isLaunchedFromHistory(intent)) {
                MainActivity.sanitizeIncomingIntent(intent)?.let(hostViewModel::submitIncomingIntent)
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
            MainActivity.sanitizeIncomingIntent(intent)?.let(hostViewModel::submitIncomingIntent)
        }

        companion object {
            internal var activeHarness: MultiPaneTestHarness? = null
        }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
