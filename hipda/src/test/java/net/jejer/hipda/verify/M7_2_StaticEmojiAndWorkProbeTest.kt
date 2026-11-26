package net.jejer.hipda.verify

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.service.WorkManagerNotificationScheduler
import net.jejer.hipda.ui.CrashLogExceptionHandler
import net.jejer.hipda.ui.HiNextApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V-M7-2 独立探针 1 与 2：
 * - 探针 1：静态依赖/源码/清单清理核查（零旧依赖、零旧包类、零旧 emoji View、零旧 Manifest 入口）与
 *   `HiNextApplication` 独立冷启动、Hilt `HiltWorkerFactory`、前后台可见性计数、崩溃处理器安装；
 * - 探针 2：`C1b#1` 全量 61 个表情四元组表驱动 + Drawable 实际加载校验；
 *   `R-D9 S3` 预埋 `"noti_work"` 周期任务后经初始化路径取消为 `CANCELLED`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34])
class M7_2_StaticEmojiAndWorkProbeTest {

    private var workManagerInitialized = false

    @After
    fun tearDown() {
        if (workManagerInitialized) {
            runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
            workManagerInitialized = false
        }
    }

    @Test
    fun probe1a_staticZeroLegacyDependenciesSourcesManifestAndAssets() {
        val repoRoot = resolveRepoRoot()

        val libsToml = File(repoRoot, "gradle/libs.versions.toml").readText()
        val hipdaGradle = File(repoRoot, "hipda/build.gradle.kts").readText()
        val emojiGradle = File(repoRoot, "emoji/build.gradle.kts").readText()
        val settingsGradle = File(repoRoot, "settings.gradle.kts").readText()

        val forbiddenBuildTokens = listOf(
            "eventbus",
            "android-priority-jobqueue",
            "materialdrawer",
            "iconics",
            "calligraphy",
            "viewpump",
            "glide",
            "gson",
            "cookie-store",
            "subsampling-scale-image-view",
            "bubble-layout",
            "emoji-java",
            "compressor",
            "androidx-appcompat",
            "androidx.appcompat",
            "androidx-preference",
            "androidx.preference",
            "com.google.android.material",
            "androidx-security-crypto",
            "androidx.security",
            "androidx-swiperefreshlayout",
            "androidx-documentfile",
            "jitpack.io",
        )
        val combinedBuildFiles = listOf(
            "libs.versions.toml" to libsToml,
            "hipda/build.gradle.kts" to hipdaGradle,
            "emoji/build.gradle.kts" to emojiGradle,
            "settings.gradle.kts" to settingsGradle,
        )
        for ((name, content) in combinedBuildFiles) {
            for (token in forbiddenBuildTokens) {
                assertFalse(
                    "Build file $name still contains forbidden legacy token: $token",
                    content.contains(token, ignoreCase = true),
                )
            }
        }

        // Deleted Java source directories and legacy entry files must not exist
        val deletedDirs = listOf(
            "hipda/src/main/java/net/jejer/hipda/async",
            "hipda/src/main/java/net/jejer/hipda/bean",
            "hipda/src/main/java/net/jejer/hipda/cache",
            "hipda/src/main/java/net/jejer/hipda/db",
            "hipda/src/main/java/net/jejer/hipda/glide",
            "hipda/src/main/java/net/jejer/hipda/job",
            "hipda/src/main/java/net/jejer/hipda/okhttp",
            "hipda/src/main/java/net/jejer/hipda/utils",
            "hipda/src/main/java/net/jejer/hipda/ui/adapter",
            "hipda/src/main/java/net/jejer/hipda/ui/setting",
            "hipda/src/main/java/net/jejer/hipda/ui/textstyle",
            "hipda/src/main/java/net/jejer/hipda/ui/widget",
            "emoji/src/main/java/com/vanniktech/emoji",
        )
        for (rel in deletedDirs) {
            val dir = File(repoRoot, rel)
            val filesInside = if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.toList()
            } else {
                emptyList()
            }
            assertTrue("Legacy directory $rel still has files: $filesInside", filesInside.isEmpty())
        }

        val deletedFiles = listOf(
            "hipda/src/main/java/net/jejer/hipda/ui/HiApplication.java",
            "hipda/src/main/java/net/jejer/hipda/ui/MainFrameActivity.java",
            "hipda/src/main/java/net/jejer/hipda/ui/IntentActivity.java",
            "hipda/src/main/java/net/jejer/hipda/service/NotiHelper.java",
            "hipda/src/main/java/net/jejer/hipda/service/NotiWorker.java",
        )
        for (rel in deletedFiles) {
            assertFalse("Deleted legacy file still exists: $rel", File(repoRoot, rel).exists())
        }

        // Scan all production source files for legacy package imports / references
        val forbiddenImportPatterns = listOf(
            Regex("""\bimport\s+net\.jejer\.hipda\.(async|bean|cache|db|glide|job|okhttp|utils)\."""),
            Regex("""\bimport\s+com\.vanniktech\.emoji\.(?!R\b)"""),
            Regex("""\bimport\s+org\.greenrobot\.eventbus\."""),
            Regex("""\bimport\s+com\.bumptech\.glide\."""),
            Regex("""\bimport\s+com\.google\.gson\."""),
            Regex("""\bimport\s+com\.mikepenz\."""),
            Regex("""\bimport\s+io\.github\.inflationx\."""),
            Regex("""\bimport\s+com\.birbit\."""),
            Regex("""\bimport\s+net\.gotev\.cookiestore\."""),
            Regex("""\bHiApplication\b"""),
            Regex("""\bMainFrameActivity\b"""),
            Regex("""\bSimpleExceptionHandler\b"""),
        )
        val prodSourceDirs = listOf(
            File(repoRoot, "hipda/src/main/java"),
            File(repoRoot, "hipda/src/debug/java"),
            File(repoRoot, "emoji/src/main/java"),
            File(repoRoot, "core-parser/src/main/kotlin"),
        )
        for (dir in prodSourceDirs) {
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { src ->
                val text = src.readText()
                // Strip block comments and line comments to avoid matching historical KDoc mentions
                val codeOnly = text
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("""//[^\n]*"""), " ")
                for (pattern in forbiddenImportPatterns) {
                    assertFalse(
                        "Production file ${src.relativeTo(repoRoot)} matched forbidden legacy reference ${pattern.pattern}",
                        pattern.containsMatchIn(codeOnly),
                    )
                }
            }
        }

        // Manifest verification
        val manifest = File(repoRoot, "hipda/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:name=".ui.HiNextApplication""""))
        assertTrue(manifest.contains("""android:name=".feature.app.MainActivity""""))
        assertTrue(manifest.contains("""android:name=".ng.IntentActivity""""))
        assertTrue(manifest.contains("""android:targetActivity=".feature.app.MainActivity""""))
        assertTrue(manifest.contains("""android:name=".ui.challenge.ChallengeActivity""""))
        assertTrue(manifest.contains("""android:name="androidx.core.content.FileProvider""""))
        assertTrue(manifest.contains("""android:authorities="${'$'}{applicationId}.provider""""))
        assertTrue(manifest.contains("""android:name="androidx.startup.InitializationProvider""""))
        for (pathPrefix in listOf("forumdisplay.php", "viewthread.php", "gotopost.php", "redirect.php", "space.php")) {
            assertTrue("Missing .ng.IntentActivity pathPrefix $pathPrefix", manifest.contains("/forum/$pathPrefix"))
        }
        for (scheme in listOf("http", "https", "mailto")) {
            assertTrue("Missing <queries> scheme $scheme", manifest.contains("""android:scheme="$scheme""""))
        }
        val legacyManifestActivities = listOf(
            "MainFrameActivity",
            "ThreadDetailActivity",
            "PostActivity",
            "ImageViewerActivity",
            "UserInfoActivity",
            "SettingActivity",
            "SmsActivity",
            "SimpleListActivity",
            ".ui.IntentActivity",
        )
        for (legacyAct in legacyManifestActivities) {
            assertFalse("Legacy activity $legacyAct still in AndroidManifest.xml", manifest.contains(legacyAct))
        }

        // Essential retained assets and XML configs must remain intact
        val licenseText = File(repoRoot, "hipda/src/main/assets/license.txt").readText()
        assertTrue("assets/license.txt missing Kyant/Apache attribution", licenseText.contains("Apache") && licenseText.contains("backdrop", ignoreCase = true))
        assertTrue(File(repoRoot, "hipda/src/main/assets/release-notes.txt").readText().isNotBlank())
        assertTrue(File(repoRoot, "hipda/src/main/assets/donors.txt").readText().isNotBlank())
        for (xmlRel in listOf(
            "hipda/src/main/res/xml/provider_paths.xml",
            "hipda/src/main/res/xml/shortcuts.xml",
            "hipda/src/main/res/xml/backup_config.xml",
            "hipda/src/main/res/xml/data_extraction_rules.xml",
        )) {
            assertTrue("Missing required XML config: $xmlRel", File(repoRoot, xmlRel).isFile)
        }
        val shortcutsXml = File(repoRoot, "hipda/src/main/res/xml/shortcuts.xml").readText()
        assertFalse("shortcuts.xml still points to MainFrameActivity", shortcutsXml.contains("MainFrameActivity"))
        assertEquals(4, Regex("""android:targetClass="net\.jejer\.hipda\.feature\.app\.MainActivity"""").findAll(shortcutsXml).count())

        // Core-parser 16 real sanitized samples verification
        val samplesManifest = File(repoRoot, "hipda/src/test/resources/samples/manifest.json").readText()
        val samplePaths = Regex(""""relativePath"\s*:\s*"([^"]+)"""").findAll(samplesManifest).map { it.groupValues[1] }.toList()
        assertEquals("Expected 16 real sanitized samples in manifest.json", 16, samplePaths.size)
        val coreParserTestText = File(repoRoot, "core-parser/src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        for (sampleRel in samplePaths) {
            assertTrue("Sample file missing: $sampleRel", File(repoRoot, "hipda/src/test/resources/samples/$sampleRel").isFile)
            val fileNameOnly = sampleRel.substringAfterLast('/')
            assertTrue(
                "Sample $sampleRel is not referenced in :core-parser/src/test!",
                coreParserTestText.contains(sampleRel) || coreParserTestText.contains(fileNameOnly) ||
                    (sampleRel == "login/login_page.html" && coreParserTestText.contains("LOGIN_PAGE")) ||
                    (sampleRel == "sms/sms_list.html" && coreParserTestText.contains("SMS_LIST")) ||
                    (sampleRel == "sms/sms_detail.html" && coreParserTestText.contains("SMS_DETAIL")) ||
                    (sampleRel == "notify/notify_list.html" && coreParserTestText.contains("NOTIFY_LIST")),
            )
        }
    }

    @Test
    fun probe1b_hiNextApplicationColdStartAndLifecycleAndHiltWorkerFactory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("Application must be HiNextApplication", app is HiNextApplication)
        val hiNextApp = app as HiNextApplication
        // HiNextApplication extends Hilt_HiNextApplication extends android.app.Application (detached from legacy HiApplication)
        assertEquals("net.jejer.hipda.ui.Hilt_HiNextApplication", hiNextApp.javaClass.superclass?.name)
        assertEquals("android.app.Application", hiNextApp.javaClass.superclass?.superclass?.name)

        // HiltWorkerFactory injected into WorkManager configuration
        val workerFactory = hiNextApp.workManagerConfiguration.workerFactory
        assertTrue("workerFactory must be HiltWorkerFactory, was ${workerFactory.javaClass.name}", workerFactory is HiltWorkerFactory)

        // CrashLogExceptionHandler installed
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(
            "Default UncaughtExceptionHandler must be CrashLogExceptionHandler, was ${handler?.javaClass?.name}",
            handler is CrashLogExceptionHandler,
        )

        // Foreground / background activity visibility counter
        val controller = Robolectric.buildActivity(DummyLifecycleActivity::class.java)
        val dummy = controller.get()
        assertFalse(HiNextApplication.isAppVisible())
        hiNextApp.onActivityStarted(dummy)
        assertTrue(HiNextApplication.isAppVisible())
        hiNextApp.onActivityStopped(dummy)
        assertFalse(HiNextApplication.isAppVisible())
        // Extra stop must clamp at 0 and not go negative
        hiNextApp.onActivityStopped(dummy)
        assertFalse(HiNextApplication.isAppVisible())
        hiNextApp.onActivityStarted(dummy)
        assertTrue(HiNextApplication.isAppVisible())
        hiNextApp.onActivityStopped(dummy)
        assertFalse(HiNextApplication.isAppVisible())
    }

    @Test
    fun probe2a_emojiMapAll61TuplesTableDrivenAndDrawableResourcesLoad() {
        val context: Context = ApplicationProvider.getApplicationContext()

        data class ExpectedEmoji(
            val code: String,
            val imageSrc: String,
            val lightDrawableName: String,
            val darkDrawableName: String,
        )

        val defaultExpected = listOf(
            ExpectedEmoji(":)", "default_smile", "default_smile", "default_smile"),
            ExpectedEmoji(":sweat:", "default_sweat", "default_sweat", "default_sweat"),
            ExpectedEmoji(":huffy:", "default_huffy", "default_huffy", "default_huffy"),
            ExpectedEmoji(":cry:", "default_cry", "default_cry", "default_cry"),
            ExpectedEmoji(":titter:", "default_titter", "default_titter", "default_titter"),
            ExpectedEmoji(":handshake:", "default_handshake", "default_handshake", "default_handshake"),
            ExpectedEmoji(":victory:", "default_victory", "default_victory", "default_victory"),
            ExpectedEmoji(":curse:", "default_curse", "default_curse", "default_curse"),
            ExpectedEmoji(":dizzy:", "default_dizzy", "default_dizzy", "default_dizzy"),
            ExpectedEmoji(":shutup:", "default_shutup", "default_shutup", "default_shutup"),
            ExpectedEmoji(":funk:", "default_funk", "default_funk", "default_funk"),
            ExpectedEmoji(":loveliness:", "default_loveliness", "default_loveliness", "default_loveliness"),
            ExpectedEmoji(":(", "default_sad", "default_sad", "default_sad"),
            ExpectedEmoji(":D", "default_biggrin", "default_biggrin", "default_biggrin"),
            ExpectedEmoji(":cool:", "default_cool", "default_cool", "default_cool"),
            ExpectedEmoji(":mad:", "default_mad", "default_mad", "default_mad"),
            ExpectedEmoji(":o", "default_shocked", "default_shocked", "default_shocked"),
            ExpectedEmoji(":P", "default_tongue", "default_tongue", "default_tongue"),
            ExpectedEmoji(":lol:", "default_lol", "default_lol", "default_lol"),
            ExpectedEmoji(":shy:", "default_shy", "default_shy", "default_shy"),
            ExpectedEmoji(":sleepy:", "default_sleepy", "default_sleepy", "default_sleepy"),
        )
        val monkeyExpected = (1..16).map { idx ->
            val num = idx.toString().padStart(2, '0')
            ExpectedEmoji(
                code = "{:2_${40 + idx}:}",
                imageSrc = "coolmonkey_$num",
                lightDrawableName = "coolmonkey_$num",
                darkDrawableName = "coolmonkey_${num}_dark",
            )
        }
        val dumbExpected = (1..24).map { idx ->
            val num = idx.toString().padStart(2, '0')
            ExpectedEmoji(
                code = "{:3_${56 + idx}:}",
                imageSrc = "grapeman_$num",
                lightDrawableName = "grapeman_$num",
                darkDrawableName = "grapeman_${num}_dark",
            )
        }
        val allExpected = defaultExpected + monkeyExpected + dumbExpected
        assertEquals(61, allExpected.size)
        assertEquals(61, allExpected.map { it.code }.distinct().size)
        assertEquals(61, allExpected.map { it.imageSrc }.distinct().size)

        // Verify group ordering, iconCode, and exact code lists
        val groups = EmojiMap.groups
        assertEquals(listOf("默认", "酷猴", "呆呆"), groups.map { it.name })
        assertEquals(listOf(21, 16, 24), groups.map { it.codes.size })
        assertEquals(defaultExpected.map { it.code }, groups[0].codes)
        assertEquals(monkeyExpected.map { it.code }, groups[1].codes)
        assertEquals(dumbExpected.map { it.code }, groups[2].codes)
        assertEquals(":)", groups[0].iconCode)
        assertEquals("{:2_41:}", groups[1].iconCode)
        assertEquals("{:3_57:}", groups[2].iconCode)

        // Verify all 61 tuples in light & dark modes and load actual Android Drawables
        for (row in allExpected) {
            val lightByCode = EmojiMap.drawableFor(row.code, dark = false)
            val darkByCode = EmojiMap.drawableFor(row.code, dark = true)
            val lightBySrc = EmojiMap.drawableForImageSrc(row.imageSrc, dark = false)
            val darkBySrc = EmojiMap.drawableForImageSrc(row.imageSrc, dark = true)

            assertNotNull("Missing light drawable for ${row.code}", lightByCode)
            assertNotNull("Missing dark drawable for ${row.code}", darkByCode)
            assertEquals(lightByCode, lightBySrc)
            assertEquals(darkByCode, darkBySrc)
            assertEquals(row.code, EmojiMap.codeForImageSrc(row.imageSrc))

            assertEquals(row.lightDrawableName, context.resources.getResourceEntryName(lightByCode!!))
            assertEquals(row.darkDrawableName, context.resources.getResourceEntryName(darkByCode!!))

            if (row.lightDrawableName != row.darkDrawableName) {
                assertNotEquals("Light/dark resource IDs must differ for ${row.code}", lightByCode, darkByCode)
            }

            val lightDrawable = ContextCompat.getDrawable(context, lightByCode)
            val darkDrawable = ContextCompat.getDrawable(context, darkByCode)
            assertNotNull("Light drawable failed to load for ${row.code}", lightDrawable)
            assertNotNull("Dark drawable failed to load for ${row.code}", darkDrawable)
            assertTrue("Light drawable empty bounds for ${row.code}", lightDrawable!!.intrinsicWidth > 0 && lightDrawable.intrinsicHeight > 0)
            assertTrue("Dark drawable empty bounds for ${row.code}", darkDrawable!!.intrinsicWidth > 0 && darkDrawable.intrinsicHeight > 0)
        }

        // Unknown entries return null
        assertNull(EmojiMap.drawableFor(":unknown:", dark = false))
        assertNull(EmojiMap.drawableForImageSrc("nonexistent_key", dark = true))
        assertNull(EmojiMap.codeForImageSrc("nonexistent_key"))
    }

    @Test
    fun probe2b_preSeededLegacyNotiWorkIsCancelledAfterColdStartInit() {
        val app = ApplicationProvider.getApplicationContext<Application>() as HiNextApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(app.workManagerConfiguration.workerFactory)
                .build(),
        )
        workManagerInitialized = true

        val wm = WorkManager.getInstance(app)

        // Pre-populate legacy periodic task "noti_work"
        val legacyReq = PeriodicWorkRequestBuilder<TestLegacyNotiDummyWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("noti_work", ExistingPeriodicWorkPolicy.KEEP, legacyReq).result.get()
        val beforeInit = wm.getWorkInfosForUniqueWork("noti_work").get()
        assertEquals(1, beforeInit.size)
        assertEquals(WorkInfo.State.ENQUEUED, beforeInit.single().state)

        // Trigger cold-start initialization via NextAppHostViewModel + WorkManagerNotificationScheduler
        val fakeSettings = InMemoryProbeSettingsRepository(AppSettings(notificationsEnabled = true))
        val scheduler = WorkManagerNotificationScheduler(context = app, settingsRepository = fakeSettings)

        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                NextAppHostViewModel(
                    settingsRepository = fakeSettings,
                    threadDetailRepository = null,
                    notificationScheduler = scheduler,
                    releaseNotesPromptStore = null,
                    currentVersionCode = 5005,
                )
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

        val afterEnabledInit = wm.getWorkInfosForUniqueWork("noti_work").get()
        assertEquals(1, afterEnabledInit.size)
        assertEquals(
            "Legacy 'noti_work' must be CANCELLED after cold-start notification sync",
            WorkInfo.State.CANCELLED,
            afterEnabledInit.single().state,
        )

        val newWork = wm.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, newWork.size)
        assertEquals(WorkInfo.State.ENQUEUED, newWork.single().state)

        // Also verify disabled branch cancels both legacy and new work
        val legacyReq2 = PeriodicWorkRequestBuilder<TestLegacyNotiDummyWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("noti_work", ExistingPeriodicWorkPolicy.REPLACE, legacyReq2).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork("noti_work").get().single().state)

        scheduler.syncSchedule(notificationsEnabled = false)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("noti_work").get().single().state)
        assertEquals(
            WorkInfo.State.CANCELLED,
            wm.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get().single().state,
        )
    }

    private fun resolveRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root")
    }
}

internal class DummyLifecycleActivity : Activity()

internal class TestLegacyNotiDummyWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

internal class InMemoryProbeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun setThemeMode(themeMode: ThemeMode) { state.value = state.value.copy(themeMode = themeMode) }
    override suspend fun setPureBlack(enabled: Boolean) { state.value = state.value.copy(pureBlack = enabled) }
    override suspend fun setThemeSeed(themeSeed: String) { state.value = state.value.copy(themeSeed = themeSeed) }
    override suspend fun setFontStep(step: Int) { state.value = state.value.copy(fontStep = step) }
    override suspend fun setLineSpacing(lineSpacing: LineSpacing) { state.value = state.value.copy(lineSpacing = lineSpacing) }
    override suspend fun setFollowedForums(forumIds: List<Int>) { state.value = state.value.copy(followedForums = forumIds) }
    override suspend fun setShowStickyThreads(show: Boolean) { state.value = state.value.copy(showStickyThreads = show) }
    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
    override suspend fun setTailEnabled(enabled: Boolean) { state.value = state.value.copy(tailEnabled = enabled) }
    override suspend fun setTailText(text: String) { state.value = state.value.copy(tailText = text) }
    override suspend fun setTailUrl(url: String) { state.value = state.value.copy(tailUrl = url) }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { state.value = state.value.copy(notificationsEnabled = enabled) }
    override suspend fun setSilentModeEnabled(enabled: Boolean) { state.value = state.value.copy(silentModeEnabled = enabled) }
    override suspend fun setSilentBeginMinutes(minutes: Int) { state.value = state.value.copy(silentBeginMinutes = minutes) }
    override suspend fun setSilentEndMinutes(minutes: Int) { state.value = state.value.copy(silentEndMinutes = minutes) }
    override suspend fun setForumServer(server: String) { state.value = state.value.copy(forumServer = server) }
    override suspend fun setImageCacheLimitMb(limitMb: Int) { state.value = state.value.copy(imageCacheLimitMb = limitMb) }
    override suspend fun setPaneSplitPortrait(split: PaneSplit) { state.value = state.value.copy(paneSplitPortrait = split) }
    override suspend fun setPaneSplitLandscape(split: PaneSplit) { state.value = state.value.copy(paneSplitLandscape = split) }
    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) { state.value = state.value.copy(sidebarCollapsedPortrait = collapsed) }
    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) { state.value = state.value.copy(sidebarCollapsedLandscape = collapsed) }
}
