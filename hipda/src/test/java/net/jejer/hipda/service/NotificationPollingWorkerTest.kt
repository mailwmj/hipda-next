package net.jejer.hipda.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.R
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.message.DefaultNotifyRepository
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.NotifyFeed
import net.jejer.hipda.data.message.NotifyReadStateStore
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.UnreadSnapshot
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
import net.jejer.hipda.parser.LegacyTestSupport
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.ui.IntentActivity
import net.jejer.hipda.utils.Constants
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationPollingWorkerTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var virtualClockMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: ServiceTestCredentialsStore
    private lateinit var settingsRepository: ServiceTestSettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        credentialsStore = ServiceTestCredentialsStore()
        server = MockWebServer()
        server.start()
        settingsRepository = ServiceTestSettingsRepository(
            AppSettings(
                forumServer = server.url("").toString().trimEnd('/'),
                notificationsEnabled = true,
                silentModeEnabled = false,
                silentBeginMinutes = AppSettings.DEFAULT_SILENT_BEGIN_MINUTES,
                silentEndMinutes = AppSettings.DEFAULT_SILENT_END_MINUTES,
            ),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private data class WorkerTestStack(
        val authRepo: DefaultAuthRepository,
        val notifyRepo: DefaultNotifyRepository,
        val poster: AndroidNotificationPoster,
    )

    private fun createStack(
        seedLoggedInAccount: SavedAccount? = SavedAccount(
            uid = "10000",
            username = "HiUser",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw"),
            lastLoginAt = 1_000L,
        ),
        waitForAuthHydration: Boolean = true,
        authCredentialsStore: CredentialsStore = credentialsStore,
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected solver call")) },
    ): WorkerTestStack {
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
            credentialsStore = authCredentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { virtualClockMs.get() },
            scope = scope,
        )
        if (seedLoggedInAccount != null && waitForAuthHydration) {
            runBlocking {
                withTimeout(3.seconds) {
                    authRepo.currentAccount.first { it?.uid == seedLoggedInAccount.uid }
                }
            }
        }
        val forumClient = ForumHttpClient(
            siteAccessGateway = primaryGateway,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
        )
        val storeFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { storeFile },
        )
        val notifyRepo = DefaultNotifyRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
            readStateStore = NotifyReadStateStore(dataStore),
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )
        val poster = AndroidNotificationPoster(context)
        return WorkerTestStack(authRepo, notifyRepo, poster)
    }

    private fun buildWorker(
        stack: WorkerTestStack,
        notifyRepository: NotifyRepository = stack.notifyRepo,
        workerCredentialsStore: CredentialsStore = credentialsStore,
        poster: NotificationPoster = stack.poster,
        appVisible: () -> Boolean = { false },
        currentMinutesOfDay: () -> Int = { QuietHoursChecker.minutesOfDay(12, 0) },
    ): NotificationPollingWorker = TestListenableWorkerBuilder<NotificationPollingWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = NotificationPollingWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    settingsRepository = settingsRepository,
                    authRepository = stack.authRepo,
                    credentialsStore = workerCredentialsStore,
                    notifyRepository = notifyRepository,
                    notificationPoster = poster,
                    appVisibilityProvider = AppVisibilityProvider { appVisible() },
                    pollingClock = PollingClock { currentMinutesOfDay() },
                )
            },
        )
        .build()

    @Test
    fun preGate1_appVisible_skipsWithZeroNetworkRequestsAndReturnsSuccess() = runBlocking {
        val stack = createStack()
        val worker = buildWorker(stack = stack, appVisible = { true })

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("App visible must issue 0 network requests", 0, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun preGate2_notificationsDisabled_skipsWithZeroNetworkRequestsAndReturnsSuccess() = runBlocking {
        val stack = createStack()
        settingsRepository.setNotificationsEnabled(false)
        val worker = buildWorker(stack = stack)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Disabled notifications must issue 0 network requests", 0, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun preGate3_quietHoursCrossMidnightAndSameDay_skipsInsideWindowAndPollsAtBoundaryOutside() = runBlocking {
        val stack = createStack()
        settingsRepository.setSilentModeEnabled(true)
        settingsRepository.setSilentBeginMinutes(QuietHoursChecker.minutesOfDay(22, 0))
        settingsRepository.setSilentEndMinutes(QuietHoursChecker.minutesOfDay(8, 0))

        val insideMinutes = listOf(
            QuietHoursChecker.minutesOfDay(22, 0),
            QuietHoursChecker.minutesOfDay(23, 59),
            QuietHoursChecker.minutesOfDay(0, 0),
            QuietHoursChecker.minutesOfDay(7, 59),
        )
        for (minute in insideMinutes) {
            val result = buildWorker(stack = stack, currentMinutesOfDay = { minute }).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }
        assertEquals("Cross-midnight quiet hours must issue 0 requests", 0, server.requestCount)

        // Same-day quiet hours 13:00–14:30
        settingsRepository.setSilentBeginMinutes(QuietHoursChecker.minutesOfDay(13, 0))
        settingsRepository.setSilentEndMinutes(QuietHoursChecker.minutesOfDay(14, 30))
        val sameDayResult = buildWorker(
            stack = stack,
            currentMinutesOfDay = { QuietHoursChecker.minutesOfDay(13, 0) },
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), sameDayResult)
        assertEquals("Same-day quiet hours must issue 0 requests", 0, server.requestCount)

        // At 14:30 (right-open boundary) polling proceeds normally!
        server.enqueue(gbkHtmlResponse(singleSmsPollHtml()))
        val outsideResult = buildWorker(
            stack = stack,
            currentMinutesOfDay = { QuietHoursChecker.minutesOfDay(14, 30) },
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), outsideResult)
        assertEquals("Boundary end minute must allow polling", 1, server.requestCount)
    }

    @Test
    fun preGate4_notLoggedInAndManualLogout_skipsWithZeroNetworkRequestsAndReturnsSuccess() = runBlocking {
        // Case A: never logged in
        val signedOutStack = createStack(seedLoggedInAccount = null)
        val signedOutResult = buildWorker(stack = signedOutStack).doWork()
        assertEquals(ListenableWorker.Result.success(), signedOutResult)
        assertEquals("Signed-out state must issue 0 network requests", 0, server.requestCount)

        // Case B: logged in then manually logged out
        val loggedInStack = createStack()
        loggedInStack.authRepo.logout()
        val loggedOutResult = buildWorker(stack = loggedInStack).doWork()
        assertEquals(ListenableWorker.Result.success(), loggedOutResult)
        assertEquals("Manually logged-out state must issue 0 network requests", 0, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun coldStartBackgroundPoll_hydratesSavedActiveAccountAndPostsSingleSmsPreviewNotification() = runBlocking {
        // R-D9 S1: Gate DefaultAuthRepository's initial credentials stream with a latch so cold-start
        // hydration waiting in NotificationPollingWorker.hasActiveAccount() is 100% deterministic.
        val savedAccount = SavedAccount(
            uid = "10000",
            username = "HiUser",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw"),
            lastLoginAt = 1_000L,
        )
        credentialsStore.upsert(savedAccount)
        credentialsStore.setActive(savedAccount.uid)

        val authHydrationStarted = CompletableDeferred<Unit>()
        val releaseAuthHydration = CompletableDeferred<Unit>()
        val gatedAuthCredentialsStore = object : CredentialsStore by credentialsStore {
            override val accounts: Flow<List<SavedAccount>> = flow {
                authHydrationStarted.complete(Unit)
                releaseAuthHydration.await()
                emitAll(credentialsStore.accounts)
            }
        }
        val workerCheckedCredentials = CompletableDeferred<Unit>()
        val observingWorkerCredentialsStore = object : CredentialsStore by credentialsStore {
            override val accounts: Flow<List<SavedAccount>> = credentialsStore.accounts.onEach {
                workerCheckedCredentials.complete(Unit)
            }
        }

        val stack = createStack(
            seedLoggedInAccount = null,
            waitForAuthHydration = false,
            authCredentialsStore = gatedAuthCredentialsStore,
        )
        withTimeout(5.seconds) { authHydrationStarted.await() }
        assertNull("authRepo.currentAccount must be null before releasing initial credentials latch", stack.authRepo.currentAccount.value)

        server.enqueue(gbkHtmlResponse(singleSmsPollHtml()))

        val workDeferred = async(Dispatchers.Default) {
            buildWorker(stack = stack, workerCredentialsStore = observingWorkerCredentialsStore).doWork()
        }
        // Worker has passed currentAccount == null check, read credentialsStore, and is awaiting hydration
        withTimeout(5.seconds) { workerCheckedCredentials.await() }
        assertNull("Auth hydration must still be gated while worker awaits currentAccount", stack.authRepo.currentAccount.value)
        assertFalse("Worker must suspend awaiting auth hydration instead of exiting early", workDeferred.isCompleted)
        assertEquals("Zero network requests before auth hydration finishes", 0, server.requestCount)

        releaseAuthHydration.complete(Unit)
        val result = withTimeout(5.seconds) { workDeferred.await() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, server.requestCount)
        assertEquals("/forum/pm.php?filter=newpm", server.takeRequest().target)

        val shadowNm = shadowOf(notificationManager)
        val channel = notificationManager.getNotificationChannel(AndroidNotificationPoster.CHANNEL_ID)
        assertNotNull("Channel HIPDA_NOTI must be created", channel)
        assertEquals(context.getString(R.string.notification_channel_name), channel.name)
        assertEquals("论坛通知", channel.name)

        val posted = shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID)
        assertNotNull("Notification must be posted for single unread SMS", posted)
        assertEquals(AndroidNotificationPoster.CHANNEL_ID, posted.channelId)
        assertEquals("HiPeer 的短消息", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("晚上有空吗😀", posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())

        val pendingIntent = posted.contentIntent
        assertNotNull(pendingIntent)
        val launchedIntent = shadowOf(pendingIntent).savedIntent
        assertEquals(IntentActivity::class.java.name, launchedIntent.component?.className)
        assertEquals(Constants.INTENT_NOTIFICATION, launchedIntent.action)
        assertEquals(1, launchedIntent.getIntExtra(Constants.EXTRA_SMS_COUNT, -1))
        assertEquals(0, launchedIntent.getIntExtra(Constants.EXTRA_THREAD_COUNT, -1))
        assertEquals("20000", launchedIntent.getStringExtra(Constants.EXTRA_UID))
        assertEquals("HiPeer", launchedIntent.getStringExtra(Constants.EXTRA_USERNAME))
    }

    @Test
    fun normalPoll_multipleSmsAndNotifications_postsTerminologyCompliantSummaryAndZeroCancels() = runBlocking {
        val stack = createStack()

        // 1. Multiple SMS (2) + multiple notifications (4)
        server.enqueue(gbkHtmlResponse(multiSmsAndNotifyPollHtml(threadCount = 3, systemPmCount = 1)))
        val firstResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), firstResult)

        val shadowNm = shadowOf(notificationManager)
        val firstNoti = shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID)
        assertNotNull(firstNoti)
        assertEquals(context.getString(R.string.notification_summary_title), firstNoti.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("HiPDA 论坛通知", firstNoti.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(
            "您有 2 条新的短消息，4 条新的提醒",
            firstNoti.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        val bannedTerms = listOf("私信", "站内信", "信息提醒", "消息提醒", "帖子提醒", "消息通知")
        val combinedText = "${firstNoti.extras.getCharSequence(Notification.EXTRA_TITLE)} ${firstNoti.extras.getCharSequence(Notification.EXTRA_TEXT)}"
        bannedTerms.forEach { banned ->
            assertTrue("Notification copy must not contain banned term '$banned': $combinedText", banned !in combinedText)
        }

        // 2. Notifications only (0 SMS, 3 notifications)
        server.enqueue(gbkHtmlResponse(notifyOnlyPollHtml(threadCount = 3)))
        val secondResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), secondResult)
        val secondNoti = shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID)
        assertNotNull(secondNoti)
        assertEquals(
            "您有 3 条新的提醒",
            secondNoti.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        // 3. Multiple SMS only (2 SMS, 0 notifications)
        server.enqueue(gbkHtmlResponse(multiSmsAndNotifyPollHtml(threadCount = 0, systemPmCount = 0)))
        val thirdResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), thirdResult)
        val thirdNoti = shadowNm.getNotification(AndroidNotificationPoster.NOTIFICATION_ID)
        assertNotNull(thirdNoti)
        assertEquals(
            "您有 2 条新的短消息",
            thirdNoti.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        // 4. Both counts drop to 0 (real empty sample) -> cancels previously posted notification
        val emptyNotifySample = LegacyTestSupport.loadSample("notify/notify_list.html")
        server.enqueue(gbkHtmlResponse(emptyNotifySample))
        val fourthResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), fourthResult)
        assertTrue(
            "Zero unread counts must cancel previously posted notification",
            shadowNm.allNotifications.isEmpty(),
        )
    }

    @Test
    fun cloudflareChallenge403_directAndDuringRelogin_abandonsSilentlyWithSuccessZeroSolverCallsAndNoRetryStorm() = runBlocking {
        val solverCalls = AtomicInteger(0)
        val stack = createStack(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Cleared
            },
        )

        // Case 1: Direct HTTP 403 cf-mitigated: challenge on pm.php?filter=newpm
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("<html><title>Just a moment...</title></html>")
                .build(),
        )
        val directResult = buildWorker(stack = stack).doWork()
        assertEquals(
            "ChallengeAbandoned MUST return Result.success() and NEVER Result.retry()",
            ListenableWorker.Result.success(),
            directResult,
        )
        assertEquals("BackgroundSilent must NEVER invoke ChallengeSolver", 0, solverCalls.get())
        assertEquals("Must not retry on challenge", 1, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())

        // Case 2: Session expired -> silent relogin hits HTTP 403 cf-mitigated: challenge
        server.enqueue(gbkHtmlResponse(loggedOutHtml()))
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("<html><title>Just a moment...</title></html>")
                .build(),
        )
        val reloginChallengeResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), reloginChallengeResult)
        assertEquals("Relogin challenge under BackgroundSilent must NEVER invoke ChallengeSolver", 0, solverCalls.get())
        assertEquals(3, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun networkErrorSiteErrorAndAuthRequired_returnSuccessWithoutRetryStorm() = runBlocking {
        val stack = createStack()

        // 1. HTTP 500 server error
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())
        val httpErrorResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), httpErrorResult)
        assertEquals(1, server.requestCount)

        // 2. Discuz! alert_error site error
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
                  <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
                  <div class="alert_error">论坛临时维护中</div>
                </body></html>
                """.trimIndent(),
            ),
        )
        val siteErrorResult = buildWorker(stack = stack).doWork()
        assertEquals(ListenableWorker.Result.success(), siteErrorResult)
        assertEquals(2, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun appBecomesVisibleMidFlight_suppressesPostingNotificationAfterPollCompletes() = runBlocking {
        val stack = createStack()
        val appVisible = AtomicBoolean(false)
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
                return gbkHtmlResponse(singleSmsPollHtml())
            }
        }

        val deferred = async(Dispatchers.Default) {
            buildWorker(stack = stack, appVisible = { appVisible.get() }).doWork()
        }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        // User opens the app while the background poll is in flight
        appVisible.set(true)
        releaseResponse.countDown()

        val result = withTimeout(5.seconds) { deferred.await() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(
            "Notification must not be posted if app became visible before poll completed",
            shadowOf(notificationManager).allNotifications.isEmpty(),
        )
    }

    @Test
    fun cancellationException_isRethrownAndNeverSwallowed() = runBlocking {
        val stack = createStack()
        val cancellingRepo = object : NotifyRepository {
            override val smsUnreadCount: StateFlow<Int> = MutableStateFlow(0)
            override val notifyUnreadCount: StateFlow<Int> = MutableStateFlow(0)
            override val notifications: StateFlow<List<NotifyEntry>> = MutableStateFlow(emptyList())
            override suspend fun getNotifications(priority: AccessPriority): MessageResult<NotifyFeed> =
                error("unused")
            override suspend fun markNotificationRead(key: String) = Unit
            override suspend fun markAllNotificationsRead() = Unit
            override suspend fun pollUnreadCounts(priority: AccessPriority): NotificationPollResult {
                throw CancellationException("Worker cancelled by WorkManager")
            }
            override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
            override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) = Unit
            override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) = Unit
        }
        val worker = buildWorker(stack = stack, notifyRepository = cancellingRepo)

        var caughtCancellation = false
        try {
            worker.doWork()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException must propagate out of doWork()", caughtCancellation)
    }

    @Test
    fun preGate4_keystoreSecurityAndProviderExceptions_returnSuccessWithZeroRequestsWhileCancellationRethrows() = runBlocking {
        val stack = createStack(seedLoggedInAccount = null)
        assertNull(stack.authRepo.currentAccount.value)

        fun throwingStore(throwable: Throwable): CredentialsStore = object : CredentialsStore {
            override val accounts: Flow<List<SavedAccount>> = flow { throw throwable }
            override val activeUid: Flow<String?> = flow { throw throwable }
            override val manuallyLoggedOut: Flow<Boolean> = flow { throw throwable }
            override suspend fun upsert(account: SavedAccount) = Unit
            override suspend fun setActive(uid: String) = Unit
            override suspend fun markManualLogout() = Unit
            override suspend fun remove(uid: String) = Unit
        }

        // 1. Keystore GeneralSecurityException during background wakeup -> gracefully skip with Result.success()
        val securityExWorker = buildWorker(
            stack = stack,
            workerCredentialsStore = throwingStore(GeneralSecurityException("AndroidKeyStore locked")),
        )
        assertEquals(ListenableWorker.Result.success(), securityExWorker.doWork())
        assertEquals(0, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())

        // 2. Keystore ProviderException -> gracefully skip with Result.success()
        val providerExWorker = buildWorker(
            stack = stack,
            workerCredentialsStore = throwingStore(ProviderException("Keystore provider unavailable")),
        )
        assertEquals(ListenableWorker.Result.success(), providerExWorker.doWork())
        assertEquals(0, server.requestCount)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())

        // 3. CancellationException while reading credentials -> must still propagate out of doWork()
        val cancelWorker = buildWorker(
            stack = stack,
            workerCredentialsStore = throwingStore(CancellationException("Cancelled reading credentials")),
        )
        var caughtCancellation = false
        try {
            cancelWorker.doWork()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException in hasActiveAccount must propagate", caughtCancellation)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rD9S2_notificationPoster_readsCopyFromStringsNotificationXmlAndCoversFallbacks() {
        assertEquals("论坛通知", context.getString(R.string.notification_channel_name))
        assertEquals("HiPDA 论坛通知", context.getString(R.string.notification_summary_title))

        val singleFallback = AndroidNotificationPoster.formatNotificationContent(
            context = context,
            snapshot = UnreadSnapshot(
                smsUnreadCount = 1,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "20001",
                    author = "   ",
                    avatarUrl = null,
                    time = "刚刚",
                    summary = "   ",
                    isNew = true,
                ),
            ),
        )
        assertEquals(context.getString(R.string.notification_single_sms_title_fallback), singleFallback.title)
        assertEquals("新短消息", singleFallback.title)
        assertEquals(context.getString(R.string.notification_single_sms_body_fallback), singleFallback.text)
        assertEquals("您有 1 条新的短消息", singleFallback.text)

        val singleNamed = AndroidNotificationPoster.formatNotificationContent(
            context = context,
            snapshot = UnreadSnapshot(
                smsUnreadCount = 1,
                notifyUnreadCount = 0,
                latestUnreadSms = SmsConversationItem(
                    uid = "20002",
                    author = "Alice",
                    avatarUrl = null,
                    time = "刚刚",
                    summary = "明天见",
                    isNew = true,
                ),
            ),
        )
        assertEquals("Alice 的短消息", singleNamed.title)
        assertEquals("明天见", singleNamed.text)

        val empty = AndroidNotificationPoster.formatNotificationContent(
            context = context,
            snapshot = UnreadSnapshot(smsUnreadCount = 0, notifyUnreadCount = 0),
        )
        assertEquals("HiPDA 论坛通知", empty.title)
        assertEquals("", empty.text)
    }

    private fun singleSmsPollHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
          <div class="promptcontent">
            <a id="prompt_pm">私人消息 (1)</a>
            <a id="prompt_systempm">系统消息 (0)</a>
            <a id="prompt_friend">好友消息 (0)</a>
            <a id="prompt_threads">帖子消息 (0)</a>
          </div>
          <ul class="pm_list">
            <li>
              <p class="cite"><cite><a href="space.php?uid=20000">HiPeer</a></cite> 2026-11-25 18:00 <img src="images/default/notice_newpm.gif" /></p>
              <div class="summary">晚上有空吗&amp;#128512;</div>
            </li>
          </ul>
        </body>
        </html>
    """.trimIndent()

    private fun multiSmsAndNotifyPollHtml(threadCount: Int, systemPmCount: Int): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
          <div class="promptcontent">
            <a id="prompt_pm">私人消息 (2)</a>
            <a id="prompt_systempm">系统消息 ($systemPmCount)</a>
            <a id="prompt_friend">好友消息 (0)</a>
            <a id="prompt_threads">帖子消息 ($threadCount)</a>
          </div>
          <ul class="pm_list">
            <li>
              <p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 18:00 <img src="images/default/notice_newpm.gif" /></p>
              <div class="summary">第一条</div>
            </li>
            <li>
              <p class="cite"><cite><a href="space.php?uid=20002">Bob</a></cite> 2026-11-25 18:05 <img src="images/default/notice_newpm.gif" /></p>
              <div class="summary">第二条</div>
            </li>
          </ul>
        </body>
        </html>
    """.trimIndent()

    private fun notifyOnlyPollHtml(threadCount: Int): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
          <div class="promptcontent">
            <a id="prompt_pm">私人消息 (0)</a>
            <a id="prompt_systempm">系统消息 (0)</a>
            <a id="prompt_friend">好友消息 (0)</a>
            <a id="prompt_threads">帖子消息 ($threadCount)</a>
          </div>
          <p class="nodata">暂无数据</p>
        </body>
        </html>
    """.trimIndent()

    private fun loggedOutHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <div class="alert_error">请先登录</div>
        </body>
        </html>
    """.trimIndent()

    private fun gbkHtmlResponse(html: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/html; charset=gbk")
        .body(Buffer().write(html.toByteArray(GBK)))
        .build()

    companion object {
        private val GBK: Charset = Charset.forName("GBK")
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal class ServiceTestCredentialsStore : CredentialsStore {
    private data class Snapshot(
        val accounts: List<SavedAccount> = emptyList(),
        val activeUid: String? = null,
        val manuallyLoggedOut: Boolean = false,
    )

    private val state = MutableStateFlow(Snapshot())
    override val accounts: Flow<List<SavedAccount>> = state.map { it.accounts }
    override val activeUid: Flow<String?> = state.map { it.activeUid }
    override val manuallyLoggedOut: Flow<Boolean> = state.map { it.manuallyLoggedOut }

    override suspend fun upsert(account: SavedAccount) {
        val normalized = account.copy(passwordMd5 = account.passwordMd5.lowercase())
        state.update { current ->
            val updated = (current.accounts.filterNot { it.uid == normalized.uid } + normalized)
                .sortedWith(compareByDescending<SavedAccount> { it.lastLoginAt }.thenBy { it.uid })
            current.copy(accounts = updated)
        }
    }

    override suspend fun setActive(uid: String) {
        state.update { current -> current.copy(activeUid = uid, manuallyLoggedOut = false) }
    }

    override suspend fun markManualLogout() {
        state.update { current -> current.copy(activeUid = null, manuallyLoggedOut = true) }
    }

    override suspend fun remove(uid: String) {
        state.update { current ->
            current.copy(
                accounts = current.accounts.filterNot { it.uid == uid },
                activeUid = if (current.activeUid == uid) null else current.activeUid,
            )
        }
    }
}

internal class ServiceTestSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override suspend fun setThemeMode(themeMode: ThemeMode) { _settings.update { it.copy(themeMode = themeMode) } }
    override suspend fun setPureBlack(enabled: Boolean) { _settings.update { it.copy(pureBlack = enabled) } }
    override suspend fun setThemeSeed(themeSeed: String) { _settings.update { it.copy(themeSeed = themeSeed) } }
    override suspend fun setFontStep(step: Int) { _settings.update { it.copy(fontStep = step) } }
    override suspend fun setLineSpacing(lineSpacing: LineSpacing) { _settings.update { it.copy(lineSpacing = lineSpacing) } }
    override suspend fun setFollowedForums(forumIds: List<Int>) { _settings.update { it.copy(followedForums = forumIds) } }
    override suspend fun setShowStickyThreads(show: Boolean) { _settings.update { it.copy(showStickyThreads = show) } }
    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) {
        _settings.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
    }
    override suspend fun setTailEnabled(enabled: Boolean) { _settings.update { it.copy(tailEnabled = enabled) } }
    override suspend fun setTailText(text: String) { _settings.update { it.copy(tailText = text) } }
    override suspend fun setTailUrl(url: String) { _settings.update { it.copy(tailUrl = url) } }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { _settings.update { it.copy(notificationsEnabled = enabled) } }
    override suspend fun setSilentModeEnabled(enabled: Boolean) { _settings.update { it.copy(silentModeEnabled = enabled) } }
    override suspend fun setSilentBeginMinutes(minutes: Int) { _settings.update { it.copy(silentBeginMinutes = minutes) } }
    override suspend fun setSilentEndMinutes(minutes: Int) { _settings.update { it.copy(silentEndMinutes = minutes) } }
    override suspend fun setForumServer(server: String) { _settings.update { it.copy(forumServer = server) } }
    override suspend fun setImageCacheLimitMb(limitMb: Int) { _settings.update { it.copy(imageCacheLimitMb = limitMb) } }
    override suspend fun setPaneSplitPortrait(split: PaneSplit) { _settings.update { it.copy(paneSplitPortrait = split) } }
    override suspend fun setPaneSplitLandscape(split: PaneSplit) { _settings.update { it.copy(paneSplitLandscape = split) } }
    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) { _settings.update { it.copy(sidebarCollapsedPortrait = collapsed) } }
    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) { _settings.update { it.copy(sidebarCollapsedLandscape = collapsed) } }
}
