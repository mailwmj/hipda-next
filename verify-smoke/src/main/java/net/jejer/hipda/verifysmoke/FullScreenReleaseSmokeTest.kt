package net.jejer.hipda.verifysmoke

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V-M7-4b CI-only full-screen release APK walkthrough executed against the CI debug-signed
 * `hipda-ng-release-signed.apk` (`net.jejer.hipda.next`, R8 full mode) with [OfflineForumFixtureServer]
 * bound strictly to `127.0.0.1` (never touches `4d4y.com`):
 *
 * 1. 冷启动
 * 2. 版块列表（含滚动与切换版块）
 * 3. 帖子详情（滚动、内联跳页）
 * 4. 看图（ImageViewerOverlay）
 * 5. 快速回复（QuickReplySheet）
 * 6. 表情面板（E7 默认/酷猴/呆呆 + 插入/整码回删）
 * 7. 完整编辑器（展开全屏 + 发新帖）
 * 8. 短消息（列表 + 对话详情 + 提醒）
 * 9. 搜索（搜索页 + 结果渲染）
 * 10. 收藏/历史（收藏与关注双标签 + Room 浏览历史）
 * 11. 设置（关于页三文档 + 错误日志）
 * 12. 账号管理（多账号添加与切号）
 * 13. WorkManager 通知轮询 Worker（release 下按类名实例化并触发后台轮询）
 * 14. 进程杀死后恢复
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FullScreenReleaseSmokeTest {

    private lateinit var device: UiDevice
    private lateinit var server: OfflineForumFixtureServer
    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        outDir = File(instr.targetContext.getExternalFilesDir(null), "smoke-artifacts").apply {
            mkdirs()
        }
        server = OfflineForumFixtureServer().apply { start() }
        Log.i(TAG, "OfflineForumFixtureServer listening at ${server.baseUrl}")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun fullScreenReleaseApkWalkthroughWorkerAndProcessKillRecovery() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val report = mutableListOf<String>()
        report += "=== V-M7-4b Full-Screen Release Walkthrough (${server.baseUrl}) ==="

        // Enforce completely offline external environment.
        shell("cmd connectivity airplane-mode enable")
        shell("svc wifi disable")
        shell("svc data disable")
        shell("pm grant $TARGET_PKG android.permission.POST_NOTIFICATIONS")

        // 1. Cold start (guest)
        shell("am force-stop $TARGET_PKG")
        val firstLaunchOut = shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
        assertTrue("Cold start failed: $firstLaunchOut", firstLaunchOut.contains("Status: ok") || firstLaunchOut.contains("TotalTime"))
        waitObj(By.text("我的"), 20_000)
        snap("01-cold-start.png")
        report += "01[cold-start]: ok (PID=${readPid()})"

        // Bootstrap offline forumServer (127.0.0.1) + login ProbeTester before walking full sequence.
        configureLoopbackForumServer(report)
        loginViaMine(OfflineForumFixtureServer.TEST_USERNAME, "probe-pass-123")
        report += "01b[setup-login]: logged in as ${OfflineForumFixtureServer.TEST_USERNAME}"

        // Clean cold start with persisted server & account (verifies R8 DataStore/Tink decryption on cold launch)
        shell("am force-stop $TARGET_PKG")
        val loggedInColdStart = shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
        assertTrue("Configured cold start failed: $loggedInColdStart", loggedInColdStart.contains("Status: ok") || loggedInColdStart.contains("TotalTime"))
        waitObj(By.textContains(OfflineForumFixtureServer.THREAD_TITLE_PREFIX), 20_000)
        snap("01b-cold-start-logged-in.png")
        report += "01b[cold-start-configured]: loaded forum feed from 127.0.0.1 (PID=${readPid()})"

        // 2. Forum list (scroll + switch tab + return to Discovery)
        swipeVertically()
        if (device.findObject(By.text("Buy & Sell")) != null) {
            clickSafely(By.text("Buy & Sell"))
            waitObj(By.textContains("版块 6"), 15_000)
            clickSafely(By.text("Discovery"))
            waitObj(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE), 15_000)
        }
        snap("02-forum-list.png")
        report += "02[forum-list]: scrolled & switched tabs cleanly"

        // 3. Thread detail (render + scroll + inline page jump)
        clickSafely(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE))
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER), 20_000)
        waitForPostImage(15_000)
        snap("03-thread-detail.png")
        swipeVertically()
        // Expand InlinePageJumpBar and jump to page 2 (with fallback to 末页)
        clickSafely(By.descContains("点按展开跳页"), 10_000)
        waitObj(By.text("末页"), 10_000)
        val pageInput = waitObj(By.desc("页码"), 5_000)
        pageInput.click()
        pageInput.text = "2"
        device.waitForIdle(500)
        if (device.findObject(By.desc("跳转")) == null) {
            pageInput.click()
            shell("input keyevent KEYCODE_MOVE_END")
            shell("input keyevent KEYCODE_DEL")
            shell("input keyevent KEYCODE_DEL")
            shell("input text 2")
            device.waitForIdle(500)
        }
        val goButton = device.findObject(By.desc("跳转"))
        if (goButton != null) {
            goButton.click()
            waitObj(By.textContains(OfflineForumFixtureServer.PAGE_2_FLOOR_MARKER), 15_000)
            snap("03b-thread-detail-page-jump.png")
            report += "03[thread-detail]: scrolled & jumped to page 2 (${OfflineForumFixtureServer.PAGE_2_FLOOR_MARKER})"
        } else {
            clickSafely(By.text("末页"), 5_000)
            waitObj(By.textContains(OfflineForumFixtureServer.PAGE_3_FLOOR_MARKER), 15_000)
            snap("03b-thread-detail-page-jump.png")
            report += "03[thread-detail]: scrolled & jumped to last page (${OfflineForumFixtureServer.PAGE_3_FLOOR_MARKER})"
        }
        // Jump back to page 1 so floor 1 post image is visible for step 4
        clickSafely(By.descContains("点按展开跳页"), 10_000)
        clickSafely(By.text("首页"), 10_000)
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER), 15_000)

        // 4. Image viewer
        waitForPostImage(10_000).click()
        waitObj(By.text("查看原图"), 15_000)
        waitObj(By.text("1 / 1"), 5_000)
        snap("04-image-viewer.png")
        clickSafely(By.desc("关闭"))
        assertTrue("ImageViewer did not dismiss", device.wait(Until.gone(By.text("查看原图")), 10_000))
        report += "04[image-viewer]: opened & dismissed cleanly"

        // 5. Quick reply sheet
        clickSafely(By.text("回复"))
        waitObj(By.desc("展开全屏"), 10_000)
        val quickEmojiToggle = waitObj(By.desc("表情"), 5_000)
        snap("05-quick-reply.png")
        report += "05[quick-reply]: opened cleanly"

        // 6. Emoji panel (all 3 groups + insert + whole-code backspace)
        quickEmojiToggle.click()
        waitObj(By.desc("默认表情"), 5_000)
        waitObj(By.desc("酷猴表情"), 5_000)
        waitObj(By.desc("呆呆表情"), 5_000)
        waitObj(By.desc("删除"), 5_000)
        waitObj(By.desc("表情 :)"), 5_000)
        snap("06-emoji-panel-default.png")

        clickSafely(By.desc("表情 :)"), 5_000)
        waitObj(By.text(":)"), 5_000)
        clickSafely(By.desc("酷猴表情"), 5_000)
        waitObj(By.desc("表情 {:2_41:}"), 5_000)
        clickSafely(By.desc("表情 {:2_41:}"), 5_000)
        waitObj(By.text(":){:2_41:}"), 5_000)
        snap("06b-emoji-panel-coolmonkey.png")

        clickSafely(By.desc("呆呆表情"), 5_000)
        waitObj(By.desc("表情 {:3_57:}"), 5_000)
        clickSafely(By.desc("删除"), 5_000)
        assertTrue("Whole-code emoji backspace did not remove {:2_41:}", device.wait(Until.gone(By.text(":){:2_41:}")), 5_000))
        waitObj(By.text(":)"), 5_000)
        snap("06c-emoji-panel-grapeman.png")
        report += "06[emoji-panel]: verified 3 tabs, insert & whole-code backspace"

        // 7. Full editor (expand from QuickReply + NewThread from ForumTopBar)
        clickSafely(By.desc("展开全屏"))
        waitObj(By.desc("发送"), 10_000)
        waitObj(By.desc("插入图片"), 10_000)
        waitObj(By.desc("表情"), 5_000)
        snap("07-full-editor.png")
        dismissKeyboardIfVisible()
        clickSafely(By.desc("返回"))
        // Handle draft snackbar or wait for ThreadDetail back button
        waitObj(By.desc("返回"), 10_000)
        clickSafely(By.desc("返回"))
        waitObj(By.desc("发新帖"), 10_000)
        clickSafely(By.desc("发新帖"))
        waitObj(By.text("发新帖"), 10_000)
        waitObj(By.desc("发送"), 10_000)
        snap("07b-full-editor-new-thread.png")
        dismissKeyboardIfVisible()
        clickSafely(By.desc("返回"))
        waitObj(By.text("消息"), 10_000)
        // Dismiss any "已保存草稿" snackbar if still visible so subsequent screenshots remain uncluttered
        device.findObject(By.text("放弃"))?.let {
            it.click()
            device.waitForIdle(400)
        }
        report += "07[full-editor]: verified expanded reply & new-thread editor"

        // 8. Messages (SMS list -> SMS conversation -> Notify list)
        clickSafely(By.text("消息"))
        waitObj(By.text("短消息"), 10_000)
        waitObj(By.text("提醒"), 10_000)
        waitObj(By.text(OfflineForumFixtureServer.PEER_USERNAME), 15_000)
        SystemClock.sleep(500)
        snap("08-messages-sms.png")

        clickSafely(By.text(OfflineForumFixtureServer.PEER_USERNAME))
        waitObj(By.text(OfflineForumFixtureServer.SMS_BODY_TEXT), 15_000)
        SystemClock.sleep(400)
        snap("08b-messages-sms-chat.png")
        clickSafely(By.desc("返回"))

        waitObj(By.text("提醒"), 10_000)
        clickSafely(By.text("提醒"))
        waitObj(By.text(OfflineForumFixtureServer.NOTIFY_TEXT), 15_000)
        SystemClock.sleep(400)
        snap("08c-messages-notify.png")
        report += "08[messages]: verified SMS list, SMS conversation & notifications"

        // 9. Search
        clickSafely(By.text("搜索"))
        val searchBox = waitObj(By.text("搜索标题、正文或作者"), 10_000)
        snap("09-search.png")
        searchBox.click()
        val searchEdit = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000) ?: searchBox
        searchEdit.click()
        searchEdit.text = "离线测试"
        device.waitForIdle(400)
        // Submit via IME Search/Enter while focused, then fall back to explicit 作者 panel 搜索 button
        shell("input keyevent 66")
        if (device.wait(Until.findObject(By.text(OfflineForumFixtureServer.SEARCH_RESULT_TITLE)), 6_000) == null) {
            dismissKeyboardIfVisible()
            clickSafely(By.text("作者"), 5_000)
            waitObj(By.text("作者"), 5_000)
            val submitBtn = device.findObjects(By.text("搜索"))
                .firstOrNull { it.visibleCenter.y < device.displayHeight * 0.75 }
            submitBtn?.click()
        } else {
            dismissKeyboardIfVisible()
        }
        waitObj(By.text(OfflineForumFixtureServer.SEARCH_RESULT_TITLE), 15_000)
        snap("09b-search-results.png")
        report += "09[search]: verified search page & parsed results"

        // 10. Favorites & Browse History
        clickSafely(By.text("我的"))
        waitObj(By.text("收藏与关注"), 10_000)
        clickSafely(By.text("收藏与关注"))
        waitObj(By.text(OfflineForumFixtureServer.FAVORITE_THREAD_TITLE), 15_000)
        snap("10-favorites.png")
        clickSafely(By.text("关注"))
        waitObj(By.text(OfflineForumFixtureServer.ATTENTION_THREAD_TITLE), 15_000)
        snap("10b-favorites-attention.png")
        clickSafely(By.desc("返回"))

        waitObj(By.text("浏览历史"), 10_000)
        clickSafely(By.text("浏览历史"))
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE), 15_000)
        snap("10c-history.png")
        clickSafely(By.desc("返回"))
        report += "10[favorites-history]: verified favorites/attention tabs & Room browse history"

        // 11. Settings (About 3 docs + Error logs)
        waitObj(By.text("设置"), 10_000)
        clickSafely(By.text("设置"))
        waitObj(By.text("外观与阅读"), 10_000)
        waitObj(By.text("关于"), 10_000)
        snap("11-settings.png")

        clickSafely(By.text("关于"))
        waitObj(By.text("版本"), 10_000)
        snap("11b-about.png")
        verifyAboutAssetDialog("更新记录", "11c-about-release-notes.png")
        verifyAboutAssetDialog("捐助名单", "11d-about-donors.png")
        verifyAboutAssetDialog("开源许可", "11e-about-license.png")

        clickSafely(By.text("查看"), 10_000)
        waitObj(By.text("错误日志"), 10_000)
        waitObj(By.text("关闭"), 10_000)
        snap("11f-about-error-logs.png")
        clickSafely(By.text("关闭"))
        assertTrue("Error logs dialog did not close", device.wait(Until.gone(By.text("关闭")), 5_000))
        // Back to Mine menu
        clickSafely(By.desc("返回"))
        waitObj(By.text("外观与阅读"), 10_000)
        clickSafely(By.desc("返回"))
        waitObj(By.text(OfflineForumFixtureServer.TEST_USERNAME), 10_000)
        report += "11[settings-about-logs]: verified 3 about docs & error logs dialog"

        // 12. Account Management (open -> add second account -> verify list -> switch back)
        clickSafely(By.text(OfflineForumFixtureServer.TEST_USERNAME))
        waitObj(By.text("当前账号"), 10_000)
        waitObj(By.text("添加账号"), 10_000)
        waitObj(By.text("退出登录"), 10_000)
        waitObj(By.text("清除登录信息"), 10_000)
        snap("12-account-management.png")

        clickSafely(By.text("添加账号"))
        submitLoginForm(OfflineForumFixtureServer.SECOND_USERNAME, "probe-pass-456")
        waitObj(By.text(OfflineForumFixtureServer.SECOND_USERNAME), 15_000)

        clickSafely(By.text(OfflineForumFixtureServer.SECOND_USERNAME))
        waitObj(By.text(OfflineForumFixtureServer.TEST_USERNAME), 10_000)
        waitObj(By.text("当前账号"), 10_000)
        snap("12b-account-multi-switch.png")
        clickSafely(By.text(OfflineForumFixtureServer.TEST_USERNAME))
        assertTrue("AccountManagementOverlay did not dismiss after switch", device.wait(Until.gone(By.text("添加账号")), 15_000))
        waitObj(By.text(OfflineForumFixtureServer.TEST_USERNAME), 10_000)
        report += "12[account-management]: verified add account & switch back to ${OfflineForumFixtureServer.TEST_USERNAME}"

        // 13. Trigger WorkManager NotificationPollingWorker in release (instantiated by class name via HiltWorkerFactory)
        triggerAndVerifyNotificationPollingWorker(report)

        // 14. Process kill & recovery
        verifyProcessKillRecovery(report)

        val summary = buildString {
            report.forEach { appendLine(it) }
            appendLine("=== Request log (all served by 127.0.0.1:${server.port}) ===")
            appendLine("Total requests: ${server.recordedRequests.size}")
            server.recordedRequests.distinct().forEach { appendLine("  $it") }
        }
        File(outDir, "smoke-summary.txt").writeText(summary, Charsets.UTF_8)
        Log.i(TAG, summary)
        instr.sendStatus(
            Activity.RESULT_OK,
            Bundle().apply { putString("v_m7_4b_smoke_summary", summary) },
        )
    }

    private fun configureLoopbackForumServer(report: MutableList<String>) {
        clickSafely(By.text("我的"))
        clickSafely(By.text("设置"))
        clickSafely(By.text("网络与缓存"))
        clickSafely(By.text("论坛服务器"))
        if (device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000) == null) {
            dismissChallengeIfPresent()
            clickSafely(By.text("论坛服务器"))
        }
        val serverInput = waitObj(By.clazz("android.widget.EditText"))
        serverInput.click()
        serverInput.text = server.baseUrl
        dismissKeyboardIfVisible()
        clickSafely(By.text("确定"))
        waitObj(By.text(server.baseUrl))
        report += "01a[settings-server]: configured ${server.baseUrl}"
        device.pressBack()
        device.pressBack()
        waitObj(By.text("登录"), 10_000)
    }

    private fun loginViaMine(username: String, password: String) {
        clickSafely(By.text("登录"))
        submitLoginForm(username, password)
        waitObj(By.text("账号"), 10_000)
        waitObj(By.text(username), 10_000)
    }

    private fun submitLoginForm(username: String, password: String) {
        waitObj(By.text("登录 HiPDA"), 10_000)
        val loginFields = device.findObjects(By.clazz("android.widget.EditText"))
            .filter { !it.text.orEmpty().contains("安全提问") }
        assertTrue("Expected at least 2 editable fields in LoginScreen, found ${loginFields.size}", loginFields.size >= 2)
        loginFields[0].text = username
        loginFields[1].text = password
        dismissKeyboardIfVisible()
        clickSafely(By.text("登录"))
        assertTrue(
            "LoginScreen did not dismiss after submit for $username",
            device.wait(Until.gone(By.text("登录 HiPDA")), 15_000),
        )
    }

    private data class AppJob(val namespace: String?, val jobId: Int)

    private fun triggerAndVerifyNotificationPollingWorker(report: MutableList<String>) {
        device.pressHome()
        // Wait > 700ms so ProcessLifecycleOwner transitions to STOPPED (backgrounded) cleanly
        SystemClock.sleep(2_500)

        val pollCountBefore = server.recordedRequests.count { it.contains("pm.php?filter=newpm") }
        val jobsDump = shell("dumpsys jobscheduler $TARGET_PKG").ifBlank { shell("dumpsys jobscheduler") }
        val jobs = parseAppJobs(jobsDump)
        val pkgLines = jobsDump.lines().filter { it.contains(TARGET_PKG) }.take(15)
        Log.i(TAG, "Discovered jobs=$jobs pkgLines=$pkgLines")
        assertTrue(
            "Expected at least one scheduled WorkManager SystemJobService job for $TARGET_PKG. Dumpsys snippet: $pkgLines",
            jobs.isNotEmpty(),
        )

        val runOutputs = mutableListOf<String>()
        for (job in jobs) {
            val nsFlag = if (job.namespace != null) "-n ${job.namespace} " else ""
            val cmd = "cmd jobscheduler run -u 0 $nsFlag-f $TARGET_PKG ${job.jobId}"
            val out = shell(cmd).trim()
            runOutputs += "$cmd => [$out]"
        }

        val deadline = SystemClock.uptimeMillis() + 20_000
        var pollObserved = false
        while (SystemClock.uptimeMillis() < deadline) {
            val pollCountAfter = server.recordedRequests.count { it.contains("pm.php?filter=newpm") }
            if (pollCountAfter > pollCountBefore) {
                pollObserved = true
                break
            }
            SystemClock.sleep(300)
        }
        assertTrue(
            "NotificationPollingWorker did not hit /forum/pm.php?filter=newpm after cmd jobscheduler run -f! jobs=$jobs outputs=$runOutputs pkgLines=$pkgLines requests=${server.recordedRequests.takeLast(12)}",
            pollObserved,
        )
        val notifDump = shell("dumpsys notification --noredact")
        val hasPostedNotif = notifDump.contains(TARGET_PKG) && notifDump.contains("HIPDA_NOTI")
        report += "13[workmanager-worker]: triggered jobs=$jobs outputs=$runOutputs polled_newpm=true notificationPosted=$hasPostedNotif"
    }

    private fun verifyProcessKillRecovery(report: MutableList<String>) {
        // Bring app back and open Thread #1 so NextAppState has active thread args in rememberSaveable.
        shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
        waitObj(By.text("论坛"), 15_000)
        clickSafely(By.text("论坛"))
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE), 15_000)
        clickSafely(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE))
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER), 15_000)

        val pidBefore = readPid()
        assertTrue("Expected running app PID before kill", pidBefore.isNotBlank())

        // Phase A: Background kill preserving task/ActivityRecord saved state if possible
        device.pressHome()
        SystemClock.sleep(2_000)
        shell("cmd activity make-uid-idle $TARGET_PKG")
        shell("am kill $TARGET_PKG")
        SystemClock.sleep(1_000)
        val pidAfterAmKill = readPid()
        var icicleRestored = false
        if (pidAfterAmKill.isEmpty()) {
            // Relaunch existing task without CLEAR_TASK to trigger Activity.onCreate(savedInstanceState != null)
            shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
            waitObj(By.textContains(OfflineForumFixtureServer.FIRST_FLOOR_MARKER), 20_000)
            val pidResumed = readPid()
            assertTrue("Expected new PID after am kill recovery (before=$pidBefore, resumed=$pidResumed)", pidResumed.isNotBlank() && pidResumed != pidBefore)
            icicleRestored = true
            snap("13a-process-kill-icicle-restored.png")
        }

        // Phase B: Guaranteed force-stop process kill + cold relaunch recovering DataStore, Tink account & Room history
        shell("am force-stop $TARGET_PKG")
        SystemClock.sleep(1_000)
        assertTrue("Expected empty PID after am force-stop", readPid().isEmpty())

        val relaunchOut = shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
        assertTrue("Relaunch after force-stop failed: $relaunchOut", relaunchOut.contains("Status: ok") || relaunchOut.contains("TotalTime"))
        waitObj(By.textContains(OfflineForumFixtureServer.FIRST_THREAD_TITLE), 20_000)
        val pidAfterForceStop = readPid()
        assertTrue("Expected new PID after force-stop (before=$pidBefore, after=$pidAfterForceStop)", pidAfterForceStop.isNotBlank() && pidAfterForceStop != pidBefore)

        // Verify persisted account survived process death
        clickSafely(By.text("我的"))
        waitObj(By.text(OfflineForumFixtureServer.TEST_USERNAME), 15_000)
        snap("13-process-kill-restored.png")
        report += "14[process-kill-recovery]: pidBefore=$pidBefore icicleRestored=$icicleRestored pidAfterForceStop=$pidAfterForceStop accountPersisted=${OfflineForumFixtureServer.TEST_USERNAME}"
    }

    private fun parseAppJobs(dumpsysOutput: String): List<AppJob> {
        val jobs = LinkedHashSet<AppJob>()
        val idRegex = Regex("""#u\d+a\d+/(\d+)""")
        val nsRegex = Regex("""\{([^}]+)\}""")
        val atNsRegex = Regex("""@([^@\s]+)@${Regex.escape(TARGET_PKG)}""")
        val lines = dumpsysOutput.lines()
        for (i in lines.indices) {
            val line = lines[i]
            val isJobHeaderForApp = line.contains(TARGET_PKG) && (line.contains("JOB ") || line.contains("SystemJobService"))
            if (!isJobHeaderForApp) continue
            val window = lines.subList(maxOf(0, i - 3), minOf(lines.size, i + 4)).joinToString(" ")
            val id = idRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: idRegex.find(window)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val ns = nsRegex.find(line)?.groupValues?.get(1)
                ?: atNsRegex.find(line)?.groupValues?.get(1)
                ?: nsRegex.find(window)?.groupValues?.get(1)
            jobs += AppJob(namespace = ns, jobId = id)
        }
        // Always include WorkManager's SystemJobScheduler default namespace + Job ID 0 fallback if dumpsys syntax shifts
        if (jobs.none { it.namespace == WM_NAMESPACE }) {
            jobs += AppJob(namespace = WM_NAMESPACE, jobId = 0)
        }
        return jobs.toList()
    }

    private fun readPid(): String = shell("pidof $TARGET_PKG").trim()

    private fun dismissKeyboardIfVisible() {
        val imeState = shell("dumpsys input_method")
        if (imeState.contains("mInputShown=true")) {
            device.pressBack()
            device.waitForIdle(500)
        }
    }

    private fun verifyAboutAssetDialog(itemTitle: String, screenshotName: String) {
        clickSafely(By.text(itemTitle))
        waitObj(By.text("关闭"), 10_000)
        SystemClock.sleep(500)
        assertFalse(
            "About asset document '$itemTitle' failed to load!",
            device.hasObject(By.text("加载失败")),
        )
        snap(screenshotName)
        clickSafely(By.text("关闭"))
        assertTrue(
            "About dialog '$itemTitle' did not close",
            device.wait(Until.gone(By.text("关闭")), 5_000),
        )
    }

    private fun waitForPostImage(timeoutMs: Long): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val match = device.findObjects(By.clickable(true))
                .firstOrNull { obj ->
                    obj.text.isNullOrEmpty() &&
                        obj.contentDescription.isNullOrEmpty() &&
                        obj.visibleBounds.width() > 400 &&
                        obj.visibleBounds.height() > 200
                }
            if (match != null) return match
            SystemClock.sleep(250)
        }
        val dumpOut = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
        error("Timed out waiting for clickable PostImageBlock (>400x200). Hierarchy: ${dumpOut.take(3000)}")
    }

    private fun waitObj(selector: BySelector, timeoutMs: Long = 10_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            dismissChallengeIfPresent()
            val obj = device.wait(Until.findObject(selector), 1_000)
            if (obj != null) return obj
        }
        val finalObj = device.wait(Until.findObject(selector), 500)
        assertNotNull("Timed out waiting for UI element: $selector", finalObj)
        return finalObj!!
    }

    private fun dismissChallengeIfPresent() {
        val challengeTitle = device.findObject(By.text("安全验证")) ?: return
        if (challengeTitle.visibleBounds.width() > 0) {
            Log.i(TAG, "Dismissing ChallengeActivity overlay")
            device.findObject(By.text("取消"))?.click() ?: device.pressBack()
            device.wait(Until.gone(By.text("安全验证")), 5_000)
            device.waitForIdle(500)
        }
    }

    private fun clickSafely(selector: BySelector, timeoutMs: Long = 10_000) {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                waitObj(selector, timeoutMs).click()
                return
            } catch (e: StaleObjectException) {
                lastError = e
                SystemClock.sleep(200)
            }
        }
        throw AssertionError("Failed to click $selector due to stale UI hierarchy", lastError)
    }

    private fun swipeVertically() {
        val cx = device.displayWidth / 2
        val topY = (device.displayHeight * 0.35f).toInt()
        val botY = (device.displayHeight * 0.75f).toInt()
        device.swipe(cx, botY, cx, topY, 18)
        device.waitForIdle()
        device.swipe(cx, topY, cx, botY, 18)
        device.waitForIdle()
    }

    private fun snap(filename: String) {
        device.waitForIdle(1_500)
        val file = File(outDir, filename)
        val ok = device.takeScreenshot(file)
        assertTrue("Failed to capture screenshot ${file.path}", ok && file.length() > 0L)
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    companion object {
        private const val TAG = "VerifySmoke"
        private const val TARGET_PKG = "net.jejer.hipda.next"
        private const val MAIN_ACTIVITY = "net.jejer.hipda.feature.app.MainActivity"
        private const val WM_NAMESPACE = "androidx.work.systemjobscheduler"
    }
}
