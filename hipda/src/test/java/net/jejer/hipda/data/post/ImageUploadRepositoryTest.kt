package net.jejer.hipda.data.post

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUploadRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var virtualClockMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: PostTestCredentialsStore
    private lateinit var settingsRepository: PostTestSettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var workDir: File
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        credentialsStore = PostTestCredentialsStore()
        workDir = tempFolder.newFolder("upload_work")
        server = MockWebServer()
        server.start()
        settingsRepository = PostTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private data class UploadTestStack(
        val authRepo: DefaultAuthRepository,
        val uploadRepo: DefaultImageUploadRepository,
    )

    private fun createStack(
        seedLoggedInAccount: SavedAccount? = SavedAccount(
            uid = "10000",
            username = "HiUser",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw"),
            lastLoginAt = 1_000L,
        ),
        solver: ChallengeSolver = ChallengeSolver {
            SolveResult.Failed(IllegalStateException("Unexpected challenge"))
        },
    ): UploadTestStack {
        if (seedLoggedInAccount != null) {
            runBlocking {
                credentialsStore.upsert(seedLoggedInAccount)
                credentialsStore.setActive(seedLoggedInAccount.uid)
            }
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient)
            .also(clientsToClose::add)
        val detector = ChallengeDetector()
        val primaryGateway = SiteAccessGateway(
            client = primaryClient,
            detector = detector,
            solver = solver,
            clock = { virtualClockMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val recoveryGateway = SiteAccessGateway(
            client = recoveryClient,
            detector = detector,
            solver = solver,
            clock = { virtualClockMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val authRepo = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { virtualClockMs.get() },
            scope = scope,
        )
        val context = ApplicationProvider.getApplicationContext<Application>()
        val compressor = ImageCompressor(
            context = context,
            workDirProvider = { workDir },
            ioDispatcher = Dispatchers.IO,
        )
        val uploadRepo = DefaultImageUploadRepository(
            siteAccessGateway = primaryGateway,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
            imageCompressor = compressor,
            nowMs = { virtualClockMs.get() },
        )
        return UploadTestStack(authRepo = authRepo, uploadRepo = uploadRepo)
    }

    @Test
    fun uploadImage_largeImageCompresses_sendsMultipartFields_emitsMonotonicProgress_andParsesImageId() = runBlocking {
        val stack = createStack()
        val largePng = TestImageFactory.writeNoisyPng(
            file = File(tempFolder.root, "upload_large.png"),
            width = 1200,
            height = 900,
        )
        val maxBytes = 48 * 1024
        assertTrue(largePng.length() > maxBytes * 2)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain; charset=gbk")
                .body("DISCUZUPLOAD|0|1721652|1")
                .build(),
        )

        val events = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(largePng, original = true),
            uid = "10000",
            hash = "deadbeef0123456789abcdef01234567",
            maxUploadBytes = maxBytes,
        ).toList()

        assertTrue("First event must be Compressing, got $events", events.first() is ImageUploadProgress.Compressing)
        val uploadingEvents = events.filterIsInstance<ImageUploadProgress.Uploading>()
        assertTrue("Expected at least one Uploading event, got $events", uploadingEvents.isNotEmpty())
        for (i in 1 until uploadingEvents.size) {
            assertTrue(
                "bytesWritten must be monotonic non-decreasing: $uploadingEvents",
                uploadingEvents[i].bytesWritten >= uploadingEvents[i - 1].bytesWritten,
            )
            assertTrue(
                "fraction must be monotonic non-decreasing: $uploadingEvents",
                uploadingEvents[i].fraction >= uploadingEvents[i - 1].fraction,
            )
        }
        val lastUpload = uploadingEvents.last()
        assertEquals(lastUpload.totalBytes, lastUpload.bytesWritten)
        assertEquals(1f, lastUpload.fraction, 0.0001f)

        val terminal = events.last()
        assertTrue("Expected Succeeded but got $terminal", terminal is ImageUploadProgress.Succeeded)
        val succeeded = terminal as ImageUploadProgress.Succeeded
        assertEquals("1721652", succeeded.imageId)
        assertTrue(succeeded.wasCompressed)
        assertTrue(succeeded.compressedBytes in 1..maxBytes)
        assertEquals(lastUpload.totalBytes, succeeded.compressedBytes)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/forum/misc.php?action=swfupload&operation=upload&simple=1&type=image",
            recorded.target,
        )
        val contentType = recorded.headers["Content-Type"].orEmpty()
        assertTrue("Expected multipart/form-data, got $contentType", contentType.startsWith("multipart/form-data"))

        val rawMultipart = recorded.body!!.utf8()
        assertTrue(rawMultipart.contains("name=\"uid\""))
        assertTrue(rawMultipart.contains("10000"))
        assertTrue(rawMultipart.contains("name=\"hash\""))
        assertTrue(rawMultipart.contains("deadbeef0123456789abcdef01234567"))
        assertTrue(rawMultipart.contains("name=\"Filedata\""))
        assertTrue(rawMultipart.contains("filename=\"Hi_"))
        assertTrue(rawMultipart.contains(".jpg\""))
        assertTrue(rawMultipart.contains("Content-Type: image/jpeg"))

        // All temporary files must be cleaned up after upload completes
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun uploadImage_compliantGifDirectUploadsAndOversizedGifFailsWithZeroRequests() = runBlocking {
        val stack = createStack()
        val compliantGif = TestImageFactory.createValidGifBytes(width = 16, height = 16, paddingBytes = 128)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("DISCUZUPLOAD|0|223344|1")
                .build(),
        )

        val gifEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.Bytes(compliantGif, mimeType = "image/gif"),
            uid = "10000",
            hash = "hash1234",
            maxUploadBytes = 4096,
        ).toList()
        val gifSuccess = gifEvents.last() as ImageUploadProgress.Succeeded
        assertEquals("223344", gifSuccess.imageId)
        assertFalse(gifSuccess.wasCompressed)
        assertEquals(compliantGif.size.toLong(), gifSuccess.compressedBytes)

        val req = server.takeRequest()
        val bodyStr = req.body!!.utf8()
        assertTrue(bodyStr.contains(".gif\""))
        assertTrue(bodyStr.contains("Content-Type: image/gif"))
        assertEquals(0, workDir.listFiles()?.size ?: 0)

        // Oversized GIF must fail without touching MockWebServer
        val oversizedGif = TestImageFactory.createValidGifBytes(width = 16, height = 16, paddingBytes = 2048)
        val countBefore = server.requestCount
        val failEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.Bytes(oversizedGif, mimeType = "image/gif"),
            uid = "10000",
            hash = "hash1234",
            maxUploadBytes = 512,
        ).toList()

        assertEquals(countBefore, server.requestCount)
        assertTrue(failEvents.first() is ImageUploadProgress.Compressing)
        val failed = failEvents.last() as ImageUploadProgress.Failed
        assertTrue(failed.error is ImageUploadError.GifTooLarge)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun uploadImage_parsesDiscuzUploadErrorsAndRetriesOnceAfterSilentReloginWhenExpired() = runBlocking {
        val stack = createStack()
        val smallJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "small_err.jpg"),
            width = 64,
            height = 64,
        )

        // 1) DISCUZUPLOAD with id 0 -> SiteRejected, no relogin
        server.enqueue(MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|0|1").build())
        val zeroIdEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "10000",
            hash = "hash1",
        ).toList()
        val zeroIdFail = zeroIdEvents.last() as ImageUploadProgress.Failed
        assertTrue(zeroIdFail.error is ImageUploadError.SiteRejected)
        assertEquals("无效上传图片ID", zeroIdFail.error.message)
        assertEquals(1, server.requestCount)
        server.takeRequest()

        // 2) Non-DISCUZUPLOAD text -> SiteRejected, no relogin
        server.enqueue(MockResponse.Builder().code(200).body("unexpected server reply").build())
        val missingMarkerEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "10000",
            hash = "hash1",
        ).toList()
        val missingFail = missingMarkerEvents.last() as ImageUploadProgress.Failed
        assertTrue(missingFail.error is ImageUploadError.SiteRejected)
        assertEquals("无法获取图片ID", missingFail.error.message)
        assertEquals(2, server.requestCount)
        server.takeRequest()

        // 3) Unauthenticated HTML -> triggers single-flight silent relogin -> replays multipart once -> succeeds
        val uploadAttempts = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("misc.php?action=swfupload") -> {
                        val attempt = uploadAttempts.incrementAndGet()
                        if (attempt == 1) {
                            gbkHtmlResponse(PostTestFixtures.loggedOutHtml("您还未登录"))
                        } else {
                            // Verify replayable request body still contains full multipart payload
                            assertTrue(request.body!!.utf8().contains("name=\"Filedata\""))
                            MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|998877|1").build()
                        }
                    }

                    target.contains("logging.php?action=login&loginsubmit=yes") -> {
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=gbk")
                            .body(PostTestFixtures.gbkBuffer("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser]]></root>"""))
                            .build()
                    }

                    target.contains("logging.php?action=login") -> {
                        gbkHtmlResponse(PostTestFixtures.loginPageHtml("loginfh1"))
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val retryEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.ContentUri(Uri.fromFile(smallJpg), original = true),
            uid = "10000",
            hash = "hash1",
        ).toList()
        val retrySuccess = retryEvents.last() as ImageUploadProgress.Succeeded
        assertEquals("998877", retrySuccess.imageId)
        assertEquals(2, uploadAttempts.get())
        assertEquals(0, workDir.listFiles()?.size ?: 0)

        // 4) Manual logout + unauthenticated response -> AuthRequired(MANUALLY_LOGGED_OUT) with 0 relogin
        stack.authRepo.logout()
        val manualLogoutAttempts = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                manualLogoutAttempts.incrementAndGet()
                return gbkHtmlResponse(PostTestFixtures.loggedOutHtml("您还未登录"))
            }
        }
        val unauthEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "10000",
            hash = "hash1",
        ).toList()
        val unauthFail = unauthEvents.last() as ImageUploadProgress.Failed
        assertTrue(unauthFail.error is ImageUploadError.AuthRequired)
        assertEquals(
            AuthRequiredReason.MANUALLY_LOGGED_OUT,
            (unauthFail.error as ImageUploadError.AuthRequired).reason,
        )
        assertEquals(1, manualLogoutAttempts.get())
    }

    @Test
    fun uploadImage_concurrentMultiUploadDoesNotBlockAndSingleCancelCleansTempWithoutAffectingPeers() = runBlocking {
        val stack = createStack()
        val img1 = TestImageFactory.writeNoisyPng(File(tempFolder.root, "concurrent_1.png"), 320, 240)
        val img2 = TestImageFactory.writeSolidJpeg(File(tempFolder.root, "concurrent_2.jpg"), 80, 80)
        val img3 = TestImageFactory.writeSolidJpeg(File(tempFolder.root, "concurrent_3.jpg"), 80, 80)

        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body!!.utf8()
                return when {
                    body.contains("hash_slow_1") -> {
                        slowStarted.countDown()
                        releaseSlow.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|111111|1").build()
                    }

                    body.contains("hash_fast_2") -> {
                        MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|222222|1").build()
                    }

                    body.contains("hash_fast_3") -> {
                        MockResponse.Builder().code(200).body("DISCUZUPLOAD|0|333333|1").build()
                    }

                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }

        withTimeout(10.seconds) {
            var caughtCancellation = false
            val slowJob = launch(Dispatchers.Default) {
                try {
                    stack.uploadRepo.uploadImage(
                        input = ImageUploadInput.LocalFile(img1, original = false),
                        uid = "10000",
                        hash = "hash_slow_1",
                        maxUploadBytes = 24 * 1024,
                    ).collect {}
                } catch (e: CancellationException) {
                    caughtCancellation = true
                    throw e
                }
            }

            assertTrue("Slow upload did not reach server", slowStarted.await(5, TimeUnit.SECONDS))

            // Start 2 concurrent uploads while slowJob is still blocked -> both must complete promptly
            val fast2 = async(Dispatchers.Default) {
                stack.uploadRepo.uploadImage(
                    input = ImageUploadInput.LocalFile(img2),
                    uid = "10000",
                    hash = "hash_fast_2",
                ).toList()
            }
            val fast3 = async(Dispatchers.Default) {
                stack.uploadRepo.uploadImage(
                    input = ImageUploadInput.LocalFile(img3),
                    uid = "10000",
                    hash = "hash_fast_3",
                ).toList()
            }

            val fast2Success = fast2.await().last() as ImageUploadProgress.Succeeded
            val fast3Success = fast3.await().last() as ImageUploadProgress.Succeeded
            assertEquals("222222", fast2Success.imageId)
            assertEquals("333333", fast3Success.imageId)

            // Cancel slow upload; CancellationException must propagate and temp file must be removed
            slowJob.cancelAndJoin()
            releaseSlow.countDown()
            assertTrue("CancellationException must not be swallowed", caughtCancellation)
            assertEquals(
                "Cancelled upload must clean all temporary files",
                0,
                workDir.listFiles()?.size ?: 0,
            )
        }
    }

    @Test
    fun dataPostSources_haveZeroZeloryCompressorReferences() {
        val candidateDirs = listOf(
            File("src/main/java/net/jejer/hipda/data/post"),
            File("hipda/src/main/java/net/jejer/hipda/data/post"),
        )
        val sourceDir = candidateDirs.firstOrNull { it.isDirectory }
            ?: error("Could not locate data/post source directory from ${File(".").absolutePath}")

        val ktFiles = sourceDir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("Expected Kotlin source files in ${sourceDir.absolutePath}", ktFiles.isNotEmpty())
        for (file in ktFiles) {
            val content = file.readText()
            assertFalse(
                "${file.name} must not reference id.zelory.compressor",
                content.contains("id.zelory.compressor"),
            )
            assertFalse(
                "${file.name} must not construct legacy Compressor",
                content.contains("Compressor("),
            )
        }
    }

    @Test
    fun uploadImage_rejectsBlankCredentialsAndMapsChallengeFailureCleanly() = runBlocking {
        val stack = createStack(
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
        val smallJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "challenge.jpg"),
            width = 64,
            height = 64,
        )

        val invalidEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "  ",
            hash = "hash1",
        ).toList()
        assertEquals(1, invalidEvents.size)
        assertTrue((invalidEvents.single() as ImageUploadProgress.Failed).error is ImageUploadError.InvalidInput)
        assertEquals(0, server.requestCount)

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body>Just a moment...</body></html>")
                .build(),
        )
        val challengeEvents = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "10000",
            hash = "hash1",
        ).toList()
        val fail = challengeEvents.last() as ImageUploadProgress.Failed
        assertTrue("Expected ChallengeRequired but got $fail", fail.error is ImageUploadError.ChallengeRequired)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun uploadImage_challengeClearedReplaysMultipartUploadAndSucceeds() = runBlocking {
        val stack = createStack(
            solver = ChallengeSolver { SolveResult.Cleared },
        )
        val smallJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "cf_replay.jpg"),
            width = 64,
            height = 64,
        )
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("<html><body>Just a moment...</body></html>")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("DISCUZUPLOAD|0|556677|1")
                .build(),
        )

        val events = stack.uploadRepo.uploadImage(
            input = ImageUploadInput.LocalFile(smallJpg),
            uid = "10000",
            hash = "hash_cf",
        ).toList()

        val succeeded = events.last() as ImageUploadProgress.Succeeded
        assertEquals("556677", succeeded.imageId)
        assertEquals(2, server.requestCount)
        // Second request must still have full multipart body (isOneShot == false)
        server.takeRequest()
        val replayed = server.takeRequest()
        assertTrue(replayed.body!!.utf8().contains("name=\"Filedata\""))
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
