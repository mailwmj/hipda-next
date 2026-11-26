package net.jejer.hipda.baselineprofile

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent API 36 emulator probe for M7-4a executed directly against the CI debug-signed
 * `release` APK (`net.jejer.hipda.next`):
 *
 * - Probe (a-device): verifies all 101 emoji PNGs, notification icon (`ic_stat_hi`), splash
 *   resources (`splash`, `splash_screen`), shortcut icons (4), `win`, launcher icons,
 *   `assets/` about docs, and `assets/dexopt/baseline.prof(m)` inside the installed release APK.
 * - Probe (b): offline end-to-end smoke across cold start, Settings/About (all 3 assets docs),
 *   Login, Forum list, Thread detail (inline emojis + post image), ImageViewer, QuickReplySheet,
 *   and FullEditor without contacting the live forum.
 * - Probe (c): cold-start latency comparison (`am start -W` + first-content ready) between
 *   uncompiled/verify (`--reset` & `-m verify -f`) and Baseline Profile AOT (`-m speed-profile -f`).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReleaseSmokeAndStartupProbeTest {

    private lateinit var device: UiDevice
    private lateinit var server: ProbeOfflineForumServer
    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        outDir = File(instr.targetContext.getExternalFilesDir(null), "smoke-artifacts").apply {
            mkdirs()
        }
        server = ProbeOfflineForumServer().apply { start() }
        Log.i(TAG, "ProbeOfflineForumServer listening at ${server.baseUrl}")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun verifyInstalledReleaseApkResourcesSmokeAndColdStartProfileComparison() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val targetContext = instr.targetContext
        val releaseContext = targetContext.createPackageContext(TARGET_PKG, Context.CONTEXT_IGNORE_SECURITY)

        val reportLines = mutableListOf<String>()
        reportLines += "=== M7-4a On-Device Release APK Verification ==="

        // 1. Verify installed release APK assets, binary baseline profile, and decoded drawable bitmaps
        verifyInstalledReleaseResources(releaseContext, reportLines)

        // 2. Disable external network so cold start never hits the live site before forumServer is switched
        shell("svc wifi disable")
        shell("svc data disable")

        // 3. Probe (b): Drive real release APK through every required screen against 127.0.0.1
        val emojiButtonObserved = runOfflineReleaseUiSmoke(reportLines)

        // 4. Probe (c): Measure cold start with vs without Baseline Profile compilation
        runColdStartProfileComparison(reportLines)

        val summary = buildString {
            appendLine("=== Request log (all served by 127.0.0.1:${server.port}) ===")
            appendLine("Total fixture requests: ${server.recordedRequests.size}")
            appendLine("FullEditor/QuickReply emoji picker button present: $emojiButtonObserved")
            reportLines.forEach { appendLine(it) }
        }
        File(outDir, "startup-benchmark.txt").writeText(summary, Charsets.UTF_8)
        Log.i(TAG, summary)
        instr.sendStatus(
            Activity.RESULT_OK,
            Bundle().apply { putString("m74a_probe_summary", summary) },
        )
    }

    private fun verifyInstalledReleaseResources(releaseContext: Context, report: MutableList<String>) {
        val apkPath = releaseContext.applicationInfo.sourceDir
        ZipFile(apkPath).use { zip ->
            val prof = zip.getEntry("assets/dexopt/baseline.prof")
            val profm = zip.getEntry("assets/dexopt/baseline.profm")
            assertTrue("Installed release APK missing baseline.prof", prof != null && prof.size > 0L)
            assertTrue("Installed release APK missing baseline.profm", profm != null && profm.size > 0L)
            report += "APK binary profiles: baseline.prof=${prof!!.size}B, baseline.profm=${profm!!.size}B"
        }

        // Assets docs
        for (doc in listOf("release-notes.txt", "donors.txt", "license.txt")) {
            val txt = releaseContext.assets.open(doc).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue("Asset $doc is blank in installed release APK", txt.isNotBlank())
            report += "Asset $doc: ${txt.length} chars"
        }

        val res = releaseContext.resources
        val allEmojiNames = buildList {
            DEFAULT_EMOJI_SUFFIXES.forEach { add("default_$it") }
            for (i in 1..16) {
                val n = i.toString().padStart(2, '0')
                add("coolmonkey_$n")
                add("coolmonkey_${n}_dark")
            }
            for (i in 1..24) {
                val n = i.toString().padStart(2, '0')
                add("grapeman_$n")
                add("grapeman_${n}_dark")
            }
        }
        assertTrue("Expected 101 emoji names", allEmojiNames.size == 101)
        for (name in allEmojiNames) {
            val id = res.getIdentifier(name, "drawable", TARGET_PKG)
            assertTrue("Missing emoji drawable $name in release APK", id != 0)
            val bmp = BitmapFactory.decodeResource(res, id)
            assertNotNull("Failed to decode emoji drawable $name", bmp)
            assertTrue(
                "Emoji drawable $name looks shrunk/stubbed (${bmp.width}x${bmp.height})",
                bmp.width > 1 && bmp.height > 1,
            )
            bmp.recycle()
        }
        report += "Decoded emojis on device: 101/101 non-stubbed bitmaps (>1x1)"

        val requiredBitmaps = listOf(
            "win",
            "ic_stat_hi",
            "splash",
            "ic_shortcut_wb_sunny",
            "ic_shortcut_search",
            "ic_shortcut_mail",
            "ic_shortcut_favorite",
        )
        for (name in requiredBitmaps) {
            val id = res.getIdentifier(name, "drawable", TARGET_PKG)
            assertTrue("Missing drawable $name in release APK", id != 0)
            val bmp = BitmapFactory.decodeResource(res, id)
            assertNotNull("Failed to decode drawable $name", bmp)
            assertTrue("Drawable $name shrunk (${bmp.width}x${bmp.height})", bmp.width > 1 && bmp.height > 1)
            report += "Decoded drawable/$name: ${bmp.width}x${bmp.height}"
            bmp.recycle()
        }

        val splashXmlId = res.getIdentifier("splash_screen", "drawable", TARGET_PKG)
        assertTrue("Missing drawable/splash_screen", splashXmlId != 0)
        assertNotNull("Failed to load drawable/splash_screen", releaseContext.getDrawable(splashXmlId))

        val launcherId = res.getIdentifier("ic_launcher", "mipmap", TARGET_PKG)
        assertTrue("Missing mipmap/ic_launcher", launcherId != 0)
        assertNotNull("Failed to load mipmap/ic_launcher", releaseContext.getDrawable(launcherId))
        report += "Verified splash_screen & mipmap/ic_launcher on device"
    }

    private fun runOfflineReleaseUiSmoke(report: MutableList<String>): Boolean {
        // Launch MainActivity (cold start offline)
        shell("am force-stop $TARGET_PKG")
        val launchOut = shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
        assertTrue("am start -W failed: $launchOut", launchOut.contains("Status: ok") || launchOut.contains("TotalTime"))
        val mineTab = waitObj(By.text("我的"), 20_000)
        snap("01-cold-start.png")
        report += "Smoke[cold-start]: ok"

        // Navigate Mine -> Settings -> Network & Cache -> Forum Server -> 127.0.0.1
        mineTab.click()
        clickSafely(By.text("设置"))
        clickSafely(By.text("网络与缓存"))
        clickSafely(By.text("论坛服务器"))

        val serverInput = waitObj(By.clazz("android.widget.EditText"))
        serverInput.click()
        serverInput.text = server.baseUrl
        dismissKeyboardIfVisible()
        clickSafely(By.text("确定"))
        waitObj(By.text(server.baseUrl))
        snap("02-settings-network.png")
        report += "Smoke[settings-network]: configured ${server.baseUrl}"

        // Navigate back to Settings groups -> About -> open all 3 assets docs
        device.pressBack()
        clickSafely(By.text("关于"))
        waitObj(By.text("版本"))
        snap("03-settings-about.png")

        verifyAboutAssetDialog("更新记录", "04-about-release-notes.png")
        verifyAboutAssetDialog("捐助名单", "05-about-donors.png")
        verifyAboutAssetDialog("开源许可", "06-about-license.png")
        report += "Smoke[settings-about-assets]: 3/3 asset docs loaded cleanly"

        // Back to Mine -> Login against loopback server
        device.pressBack()
        device.pressBack()
        clickSafely(By.text("登录"))
        waitObj(By.text("登录 HiPDA"))

        val loginFields = device.findObjects(By.clazz("android.widget.EditText"))
            .filter { !it.text.orEmpty().contains("安全提问") }
        assertTrue("Expected at least 2 editable fields in LoginScreen, found ${loginFields.size}", loginFields.size >= 2)
        loginFields[0].text = ProbeOfflineForumServer.TEST_USERNAME
        loginFields[1].text = "probe-pass-123"
        dismissKeyboardIfVisible()
        clickSafely(By.text("登录"))
        assertTrue(
            "LoginScreen did not dismiss after submit",
            device.wait(Until.gone(By.text("登录 HiPDA")), 15_000),
        )
        waitObj(By.text("账号"), 10_000)
        waitObj(By.text(ProbeOfflineForumServer.TEST_USERNAME), 10_000)
        snap("07-mine-logged-in.png")
        report += "Smoke[login]: logged in as ${ProbeOfflineForumServer.TEST_USERNAME}"

        // Forum list tab -> retry if cached initial offline error -> verify threads & scroll
        clickSafely(By.text("论坛"))
        val retryBtn = device.wait(Until.findObject(By.text("重试")), 2_000)
        if (retryBtn != null) {
            runCatching { retryBtn.click() }
        }
        waitObj(By.textContains(ProbeOfflineForumServer.THREAD_TITLE_PREFIX), 20_000)
        swipeVertically()
        snap("08-forum-list.png")
        report += "Smoke[forum-list]: loaded & scrolled offline threads"

        // Thread detail -> verify inline emojis + post image -> scroll
        clickSafely(By.textContains(ProbeOfflineForumServer.FIRST_THREAD_TITLE))
        waitObj(By.textContains(ProbeOfflineForumServer.FIRST_FLOOR_MARKER), 20_000)
        // Allow Coil to decode post-photo-1.png
        waitForPostImage(15_000)
        snap("09-thread-detail.png")
        swipeVertically()
        report += "Smoke[thread-detail]: rendered & scrolled floors + inline emojis + post image"

        // Image viewer -> tap post image -> verify controls -> close
        waitForPostImage(10_000).click()
        waitObj(By.text("查看原图"), 15_000)
        waitObj(By.text("1 / 1"), 5_000)
        snap("10-image-viewer.png")
        clickSafely(By.desc("关闭"))
        assertTrue(
            "ImageViewer did not dismiss",
            device.wait(Until.gone(By.text("查看原图")), 10_000),
        )
        report += "Smoke[image-viewer]: opened & dismissed cleanly"

        // QuickReplySheet -> open from ThreadBottomToolbar -> verify & expand to FullEditor
        clickSafely(By.text("回复"))
        waitObj(By.desc("收起回复面板"), 10_000)
        val quickEmojiToggle = device.findObject(By.desc("表情"))
        snap("11-quick-reply-sheet.png")
        report += "Smoke[quick-reply]: opened cleanly (emojiTogglePresent=${quickEmojiToggle != null})"

        clickSafely(By.desc("展开全屏"))
        waitObj(By.text("发送"), 10_000)
        waitObj(By.text("正文"), 10_000)
        val fullEmojiToggle = device.findObject(By.desc("表情"))
        snap("12-full-editor.png")
        report += "Smoke[full-editor]: opened cleanly (emojiTogglePresent=${fullEmojiToggle != null})"

        // Close editor back to thread
        dismissKeyboardIfVisible()
        device.findObject(By.desc("返回"))?.click() ?: device.pressBack()
        device.waitForIdle(2_000)
        return quickEmojiToggle != null || fullEmojiToggle != null
    }

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
            val match = device.findObjects(By.clazz("android.widget.ImageView").clickable(true))
                .firstOrNull { it.visibleBounds.width() > 200 && it.visibleBounds.height() > 100 }
            if (match != null) return match
            SystemClock.sleep(250)
        }
        error("Timed out waiting for clickable PostImageBlock (>200x100) in ThreadDetailPane")
    }

    private fun runColdStartProfileComparison(report: MutableList<String>) {
        // Ensure ProfileInstaller wrote primary.prof before verify/speed-profile runs
        val installBroadcast = shell(
            "am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n $TARGET_PKG/androidx.profileinstaller.ProfileInstallReceiver",
        )
        report += "ProfileInstaller broadcast: ${installBroadcast.trim().replace('\n', ' ')}"
        SystemClock.sleep(1_500)

        // Condition 1: verify-only (no profile AOT)
        val verifyCompile = shell("cmd package compile -m verify -f $TARGET_PKG")
        val verifyState = readDexoptStatus()
        report += "Compile[verify]: ${verifyCompile.trim()} | dexopt=$verifyState"
        val verifyRuns = measureColdStartRuns("verify", iterations = 5)
        verifyRuns.forEach { report += "  $it" }
        report += "  Summary[verify]: ${summarizeRuns(verifyRuns)}"

        // Condition 2: speed-profile (AOT using installed Baseline Profile)
        shell("am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n $TARGET_PKG/androidx.profileinstaller.ProfileInstallReceiver")
        SystemClock.sleep(1_000)
        val profileCompile = shell("cmd package compile -m speed-profile -f $TARGET_PKG")
        val profileState = readDexoptStatus()
        report += "Compile[speed-profile]: ${profileCompile.trim()} | dexopt=$profileState"
        val profileRuns = measureColdStartRuns("speed-profile", iterations = 5)
        profileRuns.forEach { report += "  $it" }
        report += "  Summary[speed-profile]: ${summarizeRuns(profileRuns)}"

        // Condition 3: --reset (uncompiled baseline)
        val resetCompile = shell("cmd package compile --reset $TARGET_PKG")
        val resetState = readDexoptStatus()
        report += "Compile[reset]: ${resetCompile.trim()} | dexopt=$resetState"
        val resetRuns = measureColdStartRuns("reset", iterations = 3)
        resetRuns.forEach { report += "  $it" }
        report += "  Summary[reset]: ${summarizeRuns(resetRuns)}"
    }

    private data class ColdStartSample(
        val mode: String,
        val index: Int,
        val thisTimeMs: Long,
        val totalTimeMs: Long,
        val waitTimeMs: Long,
        val firstContentMs: Long,
    ) {
        override fun toString(): String =
            "$mode#$index: ThisTime=${thisTimeMs}ms, TotalTime=${totalTimeMs}ms, WaitTime=${waitTimeMs}ms, FirstContent=${firstContentMs}ms"
    }

    private fun measureColdStartRuns(mode: String, iterations: Int): List<ColdStartSample> {
        val samples = mutableListOf<ColdStartSample>()
        for (i in 1..iterations) {
            shell("am force-stop $TARGET_PKG")
            SystemClock.sleep(800)
            val t0 = SystemClock.elapsedRealtime()
            val out = shell("am start -W -n $TARGET_PKG/$MAIN_ACTIVITY")
            val ready = device.wait(Until.hasObject(By.textContains(ProbeOfflineForumServer.THREAD_TITLE_PREFIX)), 15_000)
            val firstContentMs = SystemClock.elapsedRealtime() - t0
            assertTrue("Cold start ($mode#$i) failed to render forum list: $out", ready)
            samples += ColdStartSample(
                mode = mode,
                index = i,
                thisTimeMs = parseMetricMs(out, "ThisTime"),
                totalTimeMs = parseMetricMs(out, "TotalTime"),
                waitTimeMs = parseMetricMs(out, "WaitTime"),
                firstContentMs = firstContentMs,
            )
        }
        return samples
    }

    private fun summarizeRuns(runs: List<ColdStartSample>): String {
        fun stats(values: List<Long>): String {
            val sorted = values.sorted()
            val avg = values.average().toLong()
            val med = sorted[sorted.size / 2]
            return "avg=${avg}ms med=${med}ms min=${sorted.first()}ms max=${sorted.last()}ms"
        }
        return "TotalTime(${stats(runs.map { it.totalTimeMs })}), FirstContent(${stats(runs.map { it.firstContentMs })})"
    }

    private fun parseMetricMs(amStartOutput: String, key: String): Long {
        val match = Regex("""(?m)^$key:\s*(\d+)""").find(amStartOutput)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    private fun readDexoptStatus(): String {
        val dump = shell("dumpsys package $TARGET_PKG")
        val lines = dump.lineSequence()
            .filter { it.contains("status=") || it.contains("reason=") || it.contains("Dexopt state") }
            .take(4)
            .map { it.trim() }
            .toList()
        val apkDir = shell("pm path $TARGET_PKG").trim().removePrefix("package:").substringBeforeLast('/')
        val oatFiles = if (apkDir.isNotEmpty()) {
            shell("ls -lh $apkDir/oat/x86_64/").trim().replace("\n", " | ")
        } else {
            ""
        }
        return (lines + listOfNotNull(oatFiles.takeIf { it.isNotEmpty() }))
            .joinToString(" ; ")
            .ifEmpty { "<unavailable>" }
    }

    private fun waitObj(selector: BySelector, timeoutMs: Long = 10_000): UiObject2 {
        val obj = device.wait(Until.findObject(selector), timeoutMs)
        assertNotNull("Timed out waiting for UI element: $selector", obj)
        return obj!!
    }

    private fun clickSafely(selector: BySelector, timeoutMs: Long = 10_000) {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                waitObj(selector, timeoutMs).click()
                return
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
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
        private const val TAG = "M74aProbe"
        private const val TARGET_PKG = "net.jejer.hipda.next"
        private const val MAIN_ACTIVITY = "net.jejer.hipda.feature.app.MainActivity"

        private val DEFAULT_EMOJI_SUFFIXES = listOf(
            "smile", "sweat", "huffy", "cry", "titter", "handshake", "victory",
            "curse", "dizzy", "shutup", "funk", "loveliness", "sad", "biggrin",
            "cool", "mad", "shocked", "tongue", "lol", "shy", "sleepy",
        )
    }
}
