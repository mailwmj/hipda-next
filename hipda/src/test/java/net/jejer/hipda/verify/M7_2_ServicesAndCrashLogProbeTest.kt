package net.jejer.hipda.verify

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.feature.app.MainActivity
import net.jejer.hipda.feature.common.AppIntentConstants
import net.jejer.hipda.feature.imageviewer.AndroidImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.routing.AndroidAttachmentMediaStoreSaver
import net.jejer.hipda.feature.routing.AttachmentDownloadResult
import net.jejer.hipda.feature.routing.DeepLinkTarget
import net.jejer.hipda.feature.routing.OkHttpAttachmentDownloader
import net.jejer.hipda.feature.routing.parseIncomingIntent
import net.jejer.hipda.feature.settings.AndroidSettingsErrorLogStore
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.service.AndroidNotificationPoster
import net.jejer.hipda.service.NotificationPollingWorker
import net.jejer.hipda.ui.CrashLogExceptionHandler
import net.jejer.hipda.ui.HiNextApplication
import okhttp3.Headers
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * V-M7-2 补充探针 (c) 与 (d)：
 * - (c1) WorkManager + `HiltWorkerFactory` 真实构造 `NotificationPollingWorker` 与 `NotificationPoster` 通知直达 `MainActivity`；
 * - (c2) `FileProvider`（`net.jejer.hipda.next.provider`）图片分享与 URI 可读性；
 * - (c3) 鉴权附件下载（`OkHttpAttachmentDownloader` + `AndroidAttachmentMediaStoreSaver` + 路径穿越净化 + scheme 白名单）在删库后仍工作；
 * - (d) 崩溃日志写入 `filesDir/logs/crash-*.log`、10 份轮转、异常委托链及查看/复制/分享脱敏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34])
class M7_2_ServicesAndCrashLogProbeTest {

    private lateinit var app: HiNextApplication
    private lateinit var mockServer: MockWebServer
    private var savedHandler: Thread.UncaughtExceptionHandler? = null
    private var workManagerInit = false

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as HiNextApplication
        OkHttp.initialize(app)
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        mockServer = MockWebServer()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        if (workManagerInit) {
            runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
            workManagerInit = false
        }
        runCatching { mockServer.close() }
    }

    @Test
    fun probeC1_hiltWorkerFactoryConstructsNotificationPollingWorkerAndPosterRoutesToMainActivity() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(app.workManagerConfiguration.workerFactory)
                .build(),
        )
        workManagerInit = true

        // Real HiltWorkerFactory must instantiate NotificationPollingWorker with all Hilt bindings intact
        val worker = TestListenableWorkerBuilder<NotificationPollingWorker>(app)
            .setWorkerFactory(app.workManagerConfiguration.workerFactory)
            .build()
        assertTrue(worker is NotificationPollingWorker)

        // Gate 1 (foreground visible) skips without hitting network
        val dummy = DummyLifecycleActivity()
        app.onActivityStarted(dummy)
        try {
            val fgResult = runBlocking { worker.doWork() }
            assertEquals(ListenableWorker.Result.success(), fgResult)
        } finally {
            app.onActivityStopped(dummy)
        }

        // Background signed-out/no-network gate also completes cleanly with Result.success()
        val bgResult = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), bgResult)

        // NotificationPoster posts privacy-masked notification pointing to MainActivity (no legacy IntentActivity)
        val poster = AndroidNotificationPoster(app)
        poster.postUnread(
            UnreadSnapshot(
                smsUnreadCount = 1,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "54321",
                    author = "SecretSender",
                    avatarUrl = null,
                    time = "2026-11-26 12:00",
                    summary = "私密内容",
                    isNew = true,
                ),
            ),
        )
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = Shadows.shadowOf(nm).getNotification(AndroidNotificationPoster.NOTIFICATION_ID)
        assertNotNull("Expected notification posted by AndroidNotificationPoster", posted)

        val shadowPi = Shadows.shadowOf(posted.contentIntent)
        val targetIntent = shadowPi.savedIntent
        assertEquals(MainActivity::class.java.name, targetIntent.component?.className)
        assertEquals(AppIntentConstants.INTENT_NOTIFICATION, targetIntent.action)
        assertFalse(
            "Notification intent must NOT contain EXTRA_USERNAME",
            targetIntent.hasExtra(AppIntentConstants.EXTRA_USERNAME),
        )
        assertEquals(DeepLinkTarget.SmsConversation("54321"), parseIncomingIntent(targetIntent))

        // MainActivity.sanitizeIncomingIntent also strips injected EXTRA_USERNAME from untrusted intents
        val dirtyIntent = Intent(targetIntent).apply { putExtra(AppIntentConstants.EXTRA_USERNAME, "SpoofedName") }
        val sanitized = MainActivity.sanitizeIncomingIntent(dirtyIntent)!!
        assertFalse(sanitized.hasExtra(AppIntentConstants.EXTRA_USERNAME))
        assertEquals(DeepLinkTarget.SmsConversation("54321"), parseIncomingIntent(sanitized))
    }

    @Test
    fun probeC2_fileProviderShareCreatesReadableContentUriWithPermissions() {
        @Suppress("DEPRECATION")
        val providerInfo = app.packageManager.resolveContentProvider("${app.packageName}.provider", PackageManager.GET_META_DATA)
        assertNotNull("FileProvider missing for ${app.packageName}.provider", providerInfo)
        assertEquals(FileProvider::class.java.name, providerInfo!!.name)
        assertTrue("FileProvider must set grantUriPermissions=true", providerInfo.grantUriPermissions)

        val pngBytes = createPngBytes()
        val sourceFile = File(app.cacheDir, "cached_viewer_img.png").apply { writeBytes(pngBytes) }

        val fakeOriginalDownloader = object : OriginalImageDownloader {
            override fun download(url: String) =
                kotlinx.coroutines.flow.flowOf(OriginalDownloadState.Completed(sourceFile, "image/png"))
        }
        val mediaActions = AndroidImageViewerMediaActions(
            context = app,
            originalDownloader = fakeOriginalDownloader,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        val shared = runBlocking {
            mediaActions.shareImage("https://example.com/forum/test.png", cachedFile = sourceFile)
        }
        assertTrue("shareImage must succeed", shared)

        val startedChooser = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull("Chooser intent was not started", startedChooser)
        assertEquals(Intent.ACTION_CHOOSER, startedChooser.action)
        assertTrue((startedChooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        @Suppress("DEPRECATION")
        val innerSend = startedChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Inner ACTION_SEND intent missing", innerSend)
        assertEquals(Intent.ACTION_SEND, innerSend!!.action)
        assertEquals("image/png", innerSend.type)
        assertTrue((innerSend.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        @Suppress("DEPRECATION")
        val sharedUri = innerSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Shared stream URI missing", sharedUri)
        assertEquals("content", sharedUri!!.scheme)
        assertEquals("${app.packageName}.provider", sharedUri.authority)
        assertEquals(sharedUri, innerSend.clipData?.getItemAt(0)?.uri)
        assertEquals(sharedUri, startedChooser.clipData?.getItemAt(0)?.uri)

        val readBack = app.contentResolver.openInputStream(sharedUri)?.use { it.readBytes() }
        assertArrayEquals("FileProvider URI must read back exact shared image bytes", pngBytes, readBack)
    }

    @Test
    fun probeC3_attachmentDownloadWithMockWebServerSanitizesTraversalAndEnforcesWhitelist() {
        val payload = "%PDF-1.4\nM7-2 attachment verification payload\n".toByteArray(Charsets.UTF_8)
        val requestsReceived = AtomicInteger(0)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestsReceived.incrementAndGet()
                return if (request.url.encodedPath == "/forum/attachment.php") {
                    MockResponse.Builder()
                        .code(200)
                        .headers(
                            Headers.headersOf(
                                "Content-Type", "application/pdf",
                                "Content-Disposition", """attachment; filename="../../etc/passwd/../../clean_report.pdf"""",
                                "Content-Length", payload.size.toString(),
                            ),
                        )
                        .body(Buffer().write(payload))
                        .build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
            }
        }
        mockServer.start()

        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(app.workManagerConfiguration.workerFactory)
                .build(),
        )
        workManagerInit = true

        val activityController = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).setAction(AppIntentConstants.INTENT_SEARCH),
        ).create()
        val hiltDownloader = activityController.get().attachmentDownloader

        // Verify dangerous schemes are rejected at Hilt AttachmentDownloader before any network call
        for (badUrl in listOf("javascript:alert(1)", "file:///etc/passwd", "content://media/1", "mailto:a@b.com")) {
            val res = runBlocking { hiltDownloader.download(badUrl) }
            assertEquals("Scheme must be rejected: $badUrl", AttachmentDownloadResult.InvalidUrl, res)
        }
        assertEquals(0, requestsReceived.get())

        // Verify streaming download + path traversal sanitization + exact bytes on legacy Downloads path (API 28)
        val legacyDownloadsDir = File(app.cacheDir, "probe_legacy_downloads").apply {
            deleteRecursively()
            mkdirs()
        }
        val legacySaver = AndroidAttachmentMediaStoreSaver(
            context = app,
            sdkInt = Build.VERSION_CODES.P,
            hasLegacyStoragePermission = { true },
            legacyDownloadsDirProvider = { legacyDownloadsDir },
        )
        val downloaderApi28 = OkHttpAttachmentDownloader(
            callFactory = OkHttpClient(),
            saver = legacySaver,
            hasLegacyStoragePermission = { true },
            sdkInt = Build.VERSION_CODES.P,
        )
        val url = mockServer.url("/forum/attachment.php?aid=99").toString()
        val result = runBlocking { downloaderApi28.download(url) }
        assertTrue("Expected Success result, got $result", result is AttachmentDownloadResult.Success)
        val saved = (result as AttachmentDownloadResult.Success).saved
        assertEquals("clean_report.pdf", saved.fileName)
        assertEquals("application/pdf", saved.mimeType)
        assertEquals(payload.size.toLong(), saved.bytesWritten)
        assertNotNull(saved.file)
        assertEquals(legacyDownloadsDir.canonicalFile, saved.file!!.canonicalFile.parentFile)
        assertArrayEquals(payload, saved.file!!.readBytes())
        assertEquals(1, requestsReceived.get())

        // Also test Hilt-bound AttachmentDownloader (API 34 MediaStore branch)
        val api34Result = runBlocking { hiltDownloader.download(url) }
        // In Robolectric API 34, MediaStore.Downloads insert either succeeds or returns null depending on shadow MediaProvider;
        // verify it executes cleanly without ClassNotFoundException / crash and fetches from MockWebServer
        assertTrue(api34Result is AttachmentDownloadResult.Success || api34Result is AttachmentDownloadResult.Failed)
        assertEquals(2, requestsReceived.get())
        activityController.destroy()
    }

    @Test
    fun probeD_crashLogWriterAndErrorLogStoreSanitizeAllSecretsAndRotateOldLogs() {
        val logsDir = File(app.filesDir, "logs").apply {
            deleteRecursively()
            mkdirs()
        }

        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        val previousDelegate = Thread.UncaughtExceptionHandler { thread, throwable ->
            delegatedThread = thread
            delegatedError = throwable
        }

        var tick = 1_795_600_000_000L
        val handler = CrashLogExceptionHandler(
            logsDirProvider = { logsDir },
            deviceInfoProvider = { CrashLogExceptionHandler.buildDeviceInfo(app) },
            delegate = previousDelegate,
            nowProvider = { Date(tick++) },
            maxLogFiles = 10,
        )

        val rootCause = IllegalStateException(
            "SavedAccount(uid=778899, username=alice, password=RawPass!77) formhash=f00ba999 uid=778899",
        )
        val topException = RuntimeException(
            "Cookie: cdb_auth=AuthSecretXYZ; cdb_sid=SidSecret111; cf_clearance=CfSecret222; " +
                "https://www.4d4y.com/forum/space-uid-778899.html avatar/000/77/88/99 " +
                "user@private.org 198.51.100.42",
            rootCause,
        )

        handler.uncaughtException(Thread.currentThread(), topException)

        assertEquals(Thread.currentThread(), delegatedThread)
        assertEquals(topException, delegatedError)

        val crashFiles = logsDir.listFiles()?.filter { it.name.startsWith("crash-") && it.name.endsWith(".log") }.orEmpty()
        assertEquals(1, crashFiles.size)
        val rawWritten = crashFiles.single().readText(Charsets.UTF_8)

        // Device metadata preserved
        assertTrue(rawWritten.contains("设备名称 :"))
        assertTrue(rawWritten.contains("内存限制 :"))
        assertTrue(rawWritten.contains("系统版本 :"))
        assertTrue(rawWritten.contains("客户端版本 :"))
        assertTrue(rawWritten.contains("[REDACTED]"))

        // Sensitive tokens stripped at write time
        val secrets = listOf(
            "778899",
            "RawPass!77",
            "f00ba999",
            "AuthSecretXYZ",
            "SidSecret111",
            "CfSecret222",
            "user@private.org",
            "198.51.100.42",
            "000/77/88/99",
        )
        for (secret in secrets) {
            assertFalse("Written crash log leaked secret '$secret':\n$rawWritten", rawWritten.contains(secret))
        }

        // Write 12 more crash logs -> verify rotation keeps at most 10
        repeat(12) { idx ->
            val f = handler.writeCrashLog(IllegalArgumentException("crash #$idx"))
            assertNotNull(f)
            f!!.setLastModified(tick + idx * 1000L)
        }
        val remaining = logsDir.listFiles()?.filter { it.name.startsWith("crash-") && it.name.endsWith(".log") }.orEmpty()
        assertEquals("Old crash logs must be pruned to maxLogFiles=10", 10, remaining.size)

        // AndroidSettingsErrorLogStore reads from exact same filesDir/logs and redacts even un-sanitized files
        File(logsDir, "crash-99999999-raw.log").apply {
            writeText("cdb_auth=ManualRawSecret uid=665544")
            setLastModified(tick + 100_000L)
        }
        val loaded = runBlocking { AndroidSettingsErrorLogStore(app).readSanitizedLogs() }
        assertTrue(loaded.contains("crash-99999999-raw.log"))
        assertTrue(loaded.contains("[REDACTED]"))
        assertFalse(loaded.contains("ManualRawSecret"))
        assertFalse(loaded.contains("665544"))
    }

    private fun createPngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.GREEN)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
