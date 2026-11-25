package net.jejer.hipda.data.message

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
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
import net.jejer.hipda.di.MessageModule
import net.jejer.hipda.di.NotifyReadStateDataStore
import net.jejer.hipda.parser.LegacyTestSupport
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyKind
import okhttp3.OkHttp
import okhttp3.OkHttpClient
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotifyRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var virtualClockMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: MessageTestCredentialsStore
    private lateinit var settingsRepository: MessageTestSettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        credentialsStore = MessageTestCredentialsStore()
        server = MockWebServer()
        server.start()
        settingsRepository = MessageTestSettingsRepository(
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

    private data class NotifyTestStack(
        val authRepo: DefaultAuthRepository,
        val forumClient: ForumHttpClient,
        val readStateStore: NotifyReadStateStore,
        val notifyRepo: DefaultNotifyRepository,
        val storeFile: File,
    )

    private fun createStack(
        storeFile: File = File(tempFolder.newFolder(), "notify_read_state.preferences_pb"),
        repoScope: CoroutineScope = scope,
        accounts: List<SavedAccount> = listOf(
            SavedAccount(
                uid = "10000",
                username = "HiUser",
                passwordMd5 = computeLegacyPasswordMd5("pw_10000"),
                lastLoginAt = 2_000L,
            ),
        ),
        activeUid: String? = accounts.firstOrNull()?.uid,
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): NotifyTestStack {
        runBlocking {
            accounts.forEach { credentialsStore.upsert(it) }
            if (activeUid != null) {
                credentialsStore.setActive(activeUid)
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
            gatewayScope = repoScope,
            solveTimeout = 5.seconds,
        )
        val recoveryGateway = SiteAccessGateway(
            client = recoveryClient,
            detector = detector,
            solver = solver,
            clock = { virtualClockMs.get() },
            gatewayScope = repoScope,
            solveTimeout = 5.seconds,
        )
        val authRepo = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { virtualClockMs.get() },
            scope = repoScope,
        )
        val forumClient = ForumHttpClient(
            siteAccessGateway = primaryGateway,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
        )
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = repoScope,
            produceFile = { storeFile },
        )
        val readStateStore = NotifyReadStateStore(dataStore)
        val notifyRepo = DefaultNotifyRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
            readStateStore = readStateStore,
            nowMs = { virtualClockMs.get() },
            scope = repoScope,
        )
        return NotifyTestStack(authRepo, forumClient, readStateStore, notifyRepo, storeFile)
    }

    @Test
    fun getNotifications_realEmptySample_returnsEmptyFeedWithoutError() = runBlocking {
        val stack = createStack()
        val sampleHtml = LegacyTestSupport.loadSample("notify/notify_list.html")
        server.enqueue(gbkHtmlResponse(sampleHtml))

        val result = stack.notifyRepo.getNotifications()
        assertTrue("Expected Success, got $result", result is MessageResult.Success)
        val feed = (result as MessageResult.Success).data
        assertFalse(feed.hasFeedContainer)
        assertTrue(feed.entries.isEmpty())
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)
        assertEquals("/forum/notice.php", server.takeRequest().target)
    }

    @Test
    fun getNotifications_syntheticAllFiveKinds_preservesUnreadAfterLoadAndRefreshWithStableKeys() = runBlocking {
        val stack = createStack()
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))

        val firstResult = stack.notifyRepo.getNotifications()
        assertTrue("Expected Success, got $firstResult", firstResult is MessageResult.Success)
        val firstFeed = (firstResult as MessageResult.Success).data
        assertTrue(firstFeed.hasFeedContainer)
        assertEquals(5, firstFeed.entries.size)
        assertEquals(
            listOf(NotifyKind.THREAD, NotifyKind.QUOTE, NotifyKind.REPLY, NotifyKind.SYSTEM, NotifyKind.FRIEND),
            firstFeed.entries.map { it.item.kind },
        )
        // Loading notice.php must NOT mark unopened entries as read!
        assertTrue(firstFeed.entries.all { !it.isRead })
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)
        assertEquals(5, stack.notifyRepo.notifications.value.size)

        // Avatar URL should derive from SettingsRepository.forumServer
        val expectedAvatarPrefix = "${server.url("").toString().trimEnd('/')}/forum/uc_server/data/avatar/"
        assertEquals(
            "${expectedAvatarPrefix}000/02/22/22_avatar_middle.jpg",
            firstFeed.entries[1].item.avatarUrl,
        )

        // Refreshing notice.php where Discuz! stripped notice_newpm.gif (isNew=false) must keep keys identical & items unread
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = false)))
        val secondResult = stack.notifyRepo.getNotifications()
        assertTrue(secondResult is MessageResult.Success)
        val secondFeed = (secondResult as MessageResult.Success).data
        assertEquals(firstFeed.entries.map { it.key }, secondFeed.entries.map { it.key })
        assertTrue(secondFeed.entries.all { !it.isRead })
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun markNotificationRead_decrementsByOneOnlyAndPersistsAcrossRepositoryRestart() = runBlocking {
        val sharedFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstKey: String
        val allKeys: List<String>

        try {
            val stack1 = createStack(storeFile = sharedFile, repoScope = scope1)
            server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))
            val loaded = (stack1.notifyRepo.getNotifications() as MessageResult.Success).data
            allKeys = loaded.entries.map { it.key }
            firstKey = allKeys[1] // QUOTE item
            assertEquals(5, stack1.notifyRepo.notifyUnreadCount.value)

            stack1.notifyRepo.markNotificationRead(firstKey)
            assertEquals(4, stack1.notifyRepo.notifyUnreadCount.value)
            assertTrue(stack1.notifyRepo.notifications.value.first { it.key == firstKey }.isRead)
            assertEquals(4, stack1.notifyRepo.notifications.value.count { !it.isRead })

            // Marking the same key again is idempotent (still 4)
            stack1.notifyRepo.markNotificationRead(firstKey)
            assertEquals(4, stack1.notifyRepo.notifyUnreadCount.value)
        } finally {
            scope1.cancel()
            scope1.coroutineContext[Job]?.join()
        }

        // Simulate app restart with a brand new DefaultNotifyRepository on the same DataStore file
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stack2 = createStack(storeFile = sharedFile, repoScope = scope2)
            server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = false)))
            val reloaded = (stack2.notifyRepo.getNotifications() as MessageResult.Success).data

            assertEquals(allKeys, reloaded.entries.map { it.key })
            assertTrue(
                "Previously read entry must remain read across restart",
                reloaded.entries.first { it.key == firstKey }.isRead,
            )
            assertEquals(4, reloaded.entries.count { !it.isRead })
            assertEquals(4, stack2.notifyRepo.notifyUnreadCount.value)
        } finally {
            scope2.cancel()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun markAllNotificationsRead_marksAllKnownReadPersistsAndZerosNotifyUnreadWithoutTouchingSms() = runBlocking {
        val sharedFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stack1 = createStack(storeFile = sharedFile, repoScope = scope1)
            stack1.notifyRepo.updateSmsUnreadState(setOf("20001", "20002"))
            server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))
            stack1.notifyRepo.getNotifications()

            assertEquals(2, stack1.notifyRepo.smsUnreadCount.value)
            assertEquals(5, stack1.notifyRepo.notifyUnreadCount.value)

            stack1.notifyRepo.markAllNotificationsRead()

            assertEquals(0, stack1.notifyRepo.notifyUnreadCount.value)
            assertTrue(stack1.notifyRepo.notifications.value.all { it.isRead })
            assertEquals("markAllNotificationsRead must NEVER touch smsUnreadCount", 2, stack1.notifyRepo.smsUnreadCount.value)
        } finally {
            scope1.cancel()
            scope1.coroutineContext[Job]?.join()
        }

        // Reload after restart -> same 5 notifications stay read, unread stays 0
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stack2 = createStack(storeFile = sharedFile, repoScope = scope2)
            server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = false)))
            val reloaded = (stack2.notifyRepo.getNotifications() as MessageResult.Success).data
            assertTrue(reloaded.entries.all { it.isRead })
            assertEquals(0, stack2.notifyRepo.notifyUnreadCount.value)
        } finally {
            scope2.cancel()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun smsUnreadCountAndNotifyUnreadCount_updateStrictlyIndependently() = runBlocking {
        val stack = createStack()
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        // Update SMS only
        stack.notifyRepo.updateSmsUnreadState(setOf("20001", "20002", "20003"))
        assertEquals(3, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        // Update Notify via notice.php
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))
        stack.notifyRepo.getNotifications()
        assertEquals(3, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        // Mark 1 SMS conversation read -> notifyUnreadCount unchanged
        stack.notifyRepo.markSmsConversationRead("20001")
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        // Mark 1 notification read -> smsUnreadCount unchanged
        val key = stack.notifyRepo.notifications.value.first().key
        stack.notifyRepo.markNotificationRead(key)
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(4, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun updateFromPageMeta_incrementsFromHeaderWithoutClearingUnopenedLocalNotifications() = runBlocking {
        val stack = createStack()
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))
        stack.notifyRepo.getNotifications()
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        // Visiting another page whose header has 0 prompts must NOT wipe the 5 unopened local notifications
        stack.notifyRepo.updateFromPageMeta(PromptCounts(pm = 0, systemPm = 0, friend = 0, thread = 0))
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)

        // Higher server prompt counts raise the streams independently
        stack.notifyRepo.updateFromPageMeta(PromptCounts(pm = 2, systemPm = 1, friend = 2, thread = 4))
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(7, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun pollUnreadCounts_parsesSingleUnreadSmsPreviewAndPromptCountsAndCoordinatesLocalUnread() = runBlocking {
        val stack = createStack()
        val pollHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <div class="promptcontent">
                <a id="prompt_pm">私人消息 (1)</a>
                <a id="prompt_systempm">系统消息 (1)</a>
                <a id="prompt_friend">好友消息 (2)</a>
                <a id="prompt_threads">帖子消息 (3)</a>
              </div>
              <ul class="pm_list">
                <li>
                  <a class="avatar"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/02/00/00_avatar_small.jpg" /></a>
                  <p class="cite"><cite><a href="space.php?uid=20000">HiPeer</a></cite> 2026-11-25 18:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">晚上有空吗&amp;#128512;</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(pollHtml))

        val pollResult = stack.notifyRepo.pollUnreadCounts(AccessPriority.BackgroundSilent)
        assertTrue("Expected Success, got $pollResult", pollResult is NotificationPollResult.Success)
        val snapshot = (pollResult as NotificationPollResult.Success).snapshot
        assertEquals(1, snapshot.smsUnreadCount)
        assertEquals(6, snapshot.notifyUnreadCount)
        assertEquals(PromptCounts(pm = 1, systemPm = 1, friend = 2, thread = 3), snapshot.promptCounts)
        val preview = snapshot.latestUnreadSms
        assertNotNull(preview)
        assertEquals("20000", preview!!.uid)
        assertEquals("HiPeer", preview.author)
        assertEquals("晚上有空吗😀", preview.summary)
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(6, stack.notifyRepo.notifyUnreadCount.value)
        assertEquals("/forum/pm.php?filter=newpm", server.takeRequest().target)

        // Now load notice.php (5 items), then poll with prompt_threads=0 -> unopened local 5 MUST NOT be cleared!
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))
        stack.notifyRepo.getNotifications()
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        val zeroPromptPollHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <div class="promptcontent">
                <a id="prompt_systempm">系统消息 (0)</a>
                <a id="prompt_friend">好友消息 (0)</a>
                <a id="prompt_threads">帖子消息 (0)</a>
              </div>
              <p class="nodata">暂无数据</p>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(zeroPromptPollHtml))
        val secondPoll = stack.notifyRepo.pollUnreadCounts(AccessPriority.BackgroundSilent)
        assertTrue(secondPoll is NotificationPollResult.Success)
        val secondSnapshot = (secondPoll as NotificationPollResult.Success).snapshot
        assertEquals(0, secondSnapshot.smsUnreadCount)
        assertNull(secondSnapshot.latestUnreadSms)
        assertEquals(5, secondSnapshot.notifyUnreadCount)
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun pollUnreadCounts_multipleUnreadSms_clearsSinglePreviewAndReportsExactCounts() = runBlocking {
        val stack = createStack()
        val multiPmHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <ul class="pm_list">
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 10:00</p>
                  <div class="summary">msg 1</div>
                </li>
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20002">Bob</a></cite> 10:05</p>
                  <div class="summary">msg 2</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(multiPmHtml))

        val result = stack.notifyRepo.pollUnreadCounts()
        assertTrue(result is NotificationPollResult.Success)
        val snapshot = (result as NotificationPollResult.Success).snapshot
        assertEquals(2, snapshot.smsUnreadCount)
        assertNull("Single-SMS preview must be null when >1 unread SMS conversations", snapshot.latestUnreadSms)
        assertEquals(0, snapshot.notifyUnreadCount)
    }

    @Test
    fun pollUnreadCounts_backgroundSilent_forwardsPriorityAndAbandonsDirectChallengeWithoutMutatingCounts() = runBlocking {
        val solverCalls = AtomicInteger(0)
        val stack = createStack(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Cleared
            },
        )
        // Pre-seed existing counts to verify abandonment leaves them untouched
        stack.notifyRepo.updateSmsUnreadState(setOf("20001"))
        stack.notifyRepo.updateFromPageMeta(PromptCounts(thread = 4))
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(4, stack.notifyRepo.notifyUnreadCount.value)

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("cf challenge")
                .build(),
        )

        val result = stack.notifyRepo.pollUnreadCounts(AccessPriority.BackgroundSilent)
        assertEquals(NotificationPollResult.ChallengeAbandoned, result)
        assertEquals("BackgroundSilent must never invoke ChallengeSolver", 0, solverCalls.get())
        assertEquals("Existing smsUnreadCount must remain untouched", 1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals("Existing notifyUnreadCount must remain untouched", 4, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun pollUnreadCounts_backgroundSilent_abandonsReloginChallengeWithoutInvokingSolverOrMutatingCounts() = runBlocking {
        val solverCalls = AtomicInteger(0)
        val stack = createStack(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Cleared
            },
        )
        stack.notifyRepo.updateSmsUnreadState(setOf("20001", "20002"))
        stack.notifyRepo.updateFromPageMeta(PromptCounts(thread = 3))

        // First GET returns logged-out HTML; ForumHttpClient forwards BackgroundSilent to trySilentRelogin,
        // which hits a Cloudflare 403 challenge and returns Failed(CHALLENGE_ABANDONED) inside AuthRequiredException.
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loggedOutHtml("需要登录")))
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("cf challenge on login")
                .build(),
        )

        val result = stack.notifyRepo.pollUnreadCounts(AccessPriority.BackgroundSilent)
        assertEquals(NotificationPollResult.ChallengeAbandoned, result)
        assertEquals(0, solverCalls.get())
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(3, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun pollUnreadCounts_manualLogout_returnsAuthRequiredWithZeroReloginRequests() = runBlocking {
        val stack = createStack()
        stack.authRepo.logout()

        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loggedOutHtml("请先登录")))
        val result = stack.notifyRepo.pollUnreadCounts(AccessPriority.BackgroundSilent)
        assertEquals(NotificationPollResult.AuthRequired(AuthRequiredReason.MANUALLY_LOGGED_OUT), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accountSwitchAndLogout_isolatesReadStateAndImmediatelyResetsUnreadStreams() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 2_000L)
        val accountB = SavedAccount("10002", "Bob", computeLegacyPasswordMd5("pwB"), lastLoginAt = 1_000L)
        val stack = createStack(accounts = listOf(accountA, accountB), activeUid = accountA.uid)

        // Account A loads 5 notifications and marks entry 0 read, plus has 2 unread SMS
        stack.notifyRepo.updateSmsUnreadState(setOf("30001", "30002"))
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true, uid = "10001", username = "Alice")))
        val feedA = (stack.notifyRepo.getNotifications() as MessageResult.Success).data
        val readKeyInA = feedA.entries.first().key
        stack.notifyRepo.markNotificationRead(readKeyInA)
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(4, stack.notifyRepo.notifyUnreadCount.value)

        // Switch to Account B via login endpoints
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loginFormHtml("fh_switch_b")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Bob]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=bob_cookie; Path=/"),
            ),
        )
        stack.authRepo.switchAccount(accountB.uid)

        // Wait for reactive reset to zero counts for Account B
        withTimeout(3.seconds) {
            stack.notifyRepo.smsUnreadCount.first { it == 0 }
            stack.notifyRepo.notifyUnreadCount.first { it == 0 }
        }
        assertTrue(stack.notifyRepo.notifications.value.isEmpty())

        // Account B loads the exact same notification feed -> entry 0 MUST be UNREAD for Account B!
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true, uid = "10002", username = "Bob")))
        val feedB = (stack.notifyRepo.getNotifications() as MessageResult.Success).data
        assertFalse("Read state from Account A must NOT leak into Account B", feedB.entries.first().isRead)
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        // Logout immediately clears in-memory unread counts and notification list
        stack.authRepo.logout()
        withTimeout(3.seconds) {
            stack.notifyRepo.smsUnreadCount.first { it == 0 }
            stack.notifyRepo.notifyUnreadCount.first { it == 0 }
            stack.notifyRepo.notifications.first { it.isEmpty() }
        }
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        // Switch back to Account A -> Account A's read state is intact (4 unread, entry 0 read)
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loginFormHtml("fh_switch_a")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=alice_cookie; Path=/"),
            ),
        )
        stack.authRepo.switchAccount(accountA.uid)
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = false, uid = "10001", username = "Alice")))
        val feedAReloaded = (stack.notifyRepo.getNotifications() as MessageResult.Success).data
        assertTrue(feedAReloaded.entries.first { it.key == readKeyInA }.isRead)
        assertEquals(4, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun corruptedNotifyReadStateProtoFile_recoversGracefullyWithoutCrashing() = runBlocking {
        val corruptedFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        corruptedFile.writeBytes(byteArrayOf(0x01, 0xFF.toByte(), 0xAA.toByte(), 0x00, 0x19))

        val stack = createStack(storeFile = corruptedFile)
        server.enqueue(gbkHtmlResponse(syntheticNotifyFeedHtml(includeNewMarkers = true)))

        val result = stack.notifyRepo.getNotifications()
        assertTrue("Expected Success after corruption recovery, got $result", result is MessageResult.Success)
        val feed = (result as MessageResult.Success).data
        assertEquals(5, feed.entries.size)
        assertEquals(5, stack.notifyRepo.notifyUnreadCount.value)

        stack.notifyRepo.markNotificationRead(feed.entries.first().key)
        assertEquals(4, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun markAllNotificationsRead_beforeNoticeLoad_suppressesStaleServerCountAndShowsOnlyNewArrivals() = runBlocking {
        val stack = createStack()
        // Wait for auth hydration so account 10000 is active
        withTimeout(3.seconds) { stack.authRepo.currentAccount.first { it?.uid == "10000" } }

        stack.notifyRepo.updateFromPageMeta(PromptCounts(thread = 3))
        assertEquals(3, stack.notifyRepo.notifyUnreadCount.value)

        stack.notifyRepo.markAllNotificationsRead()
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        // Same server prompt count stays suppressed at 0
        stack.notifyRepo.updateFromPageMeta(PromptCounts(thread = 3))
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        // New notification arrives on server (3 -> 4) -> delta 1 appears immediately
        stack.notifyRepo.updateFromPageMeta(PromptCounts(thread = 4))
        assertEquals(1, stack.notifyRepo.notifyUnreadCount.value)
    }

    @Test
    fun messageModule_hasExpectedHiltBindingsAndUnencryptedDataStoreFileName() {
        assertTrue(MessageModule::class.java.isAnnotationPresent(Module::class.java))
        assertEquals("notify_read_state", MessageModule.NOTIFY_READ_STATE_STORE_NAME)

        val context: Context = ApplicationProvider.getApplicationContext()
        val dataStoreFile = MessageModule.notifyReadStateDataStoreFile(context)
        assertEquals("notify_read_state.preferences_pb", dataStoreFile.name)

        val bindSms = MessageModule::class.java.getDeclaredMethod("bindSmsRepository", DefaultSmsRepository::class.java)
        assertTrue(bindSms.isAnnotationPresent(Binds::class.java))
        assertTrue(bindSms.isAnnotationPresent(Singleton::class.java))
        assertEquals(SmsRepository::class.java, bindSms.returnType)

        val bindNotify = MessageModule::class.java.getDeclaredMethod("bindNotifyRepository", DefaultNotifyRepository::class.java)
        assertTrue(bindNotify.isAnnotationPresent(Binds::class.java))
        assertTrue(bindNotify.isAnnotationPresent(Singleton::class.java))
        assertEquals(NotifyRepository::class.java, bindNotify.returnType)

        val provideStore = MessageModule.Companion::class.java.getDeclaredMethod(
            "provideNotifyReadStateDataStore",
            Context::class.java,
        )
        assertTrue(provideStore.isAnnotationPresent(Provides::class.java))
        assertTrue(provideStore.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideStore.isAnnotationPresent(NotifyReadStateDataStore::class.java))
        assertEquals(DataStore::class.java, provideStore.returnType)
    }

    private fun syntheticNotifyFeedHtml(
        includeNewMarkers: Boolean,
        uid: String = "10000",
        username: String = "HiUser",
    ): String {
        val newImg = if (includeNewMarkers) """<img src="images/default/notice_newpm.gif" />""" else ""
        return """
            <!DOCTYPE html>
            <html>
            <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
            <body>
              <div id="umenu">
                <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
                <a href="logging.php?action=logout&amp;formhash=00000000">退出</a>
              </div>
              <div class="promptcontent">
                <a id="prompt_pm">私人消息 (0)</a>
                <a id="prompt_systempm">系统消息 (0)</a>
                <a id="prompt_friend">好友消息 (0)</a>
                <a id="prompt_threads">帖子消息 (0)</a>
              </div>
              <ul class="feed">
                <li>
                  <div class="f_thread">
                    <a href="space.php?uid=11111">Alice</a> 回复了您的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9001&amp;ptid=8001">普通主题&amp;#128512;</a>
                    <em>2026-11-20 10:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_quote">
                    $newImg
                    <a href="space.php?uid=22222">Bob</a> 引用了您在
                    <a href="viewthread.php?tid=8002">引用测试帖</a> 中的回复
                    <a href="redirect.php?goto=findpost&amp;pid=9002&amp;ptid=8002">查看</a>
                    <em>2026-11-20 11:00</em>
                    <dl class="summary"><dd>原帖内容</dd><dd>引用回复</dd></dl>
                  </div>
                </li>
                <li>
                  <div class="f_reply">
                    $newImg
                    <a href="space.php?uid=22223">Carol</a> 回复了您在
                    <a href="viewthread.php?tid=8003">回复测试帖</a> 中的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9003&amp;ptid=8003">查看</a>
                    <em>2026-11-20 12:00</em>
                    <div class="summary">单段摘要文本</div>
                  </div>
                </li>
                <li>
                  <div class="f_manage">
                    $newImg
                    您的主题 <a href="viewthread.php?tid=8004">系统通知帖</a> 已被加精 <em>2026-11-20 13:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_buddy">
                    $newImg
                    <a href="space.php?uid=33333">Dave</a> 请求加您为好友
                    <a href="my.php?item=buddylist&amp;newbuddyid=33333">批准申请</a>
                    <em>2026-11-20 14:00</em>
                  </div>
                </li>
              </ul>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
