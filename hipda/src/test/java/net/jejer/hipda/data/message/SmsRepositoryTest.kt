package net.jejer.hipda.data.message

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
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
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.parser.SampleTestLoader
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class SmsRepositoryTest {

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
        runBlocking { scope.coroutineContext[Job]?.join() }
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private data class SmsTestStack(
        val authRepo: DefaultAuthRepository,
        val notifyRepo: DefaultNotifyRepository,
        val smsRepo: DefaultSmsRepository,
    )

    private fun createStack(
        seedLoggedInAccount: SavedAccount? = SavedAccount(
            uid = "10000",
            username = "HiUser",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw"),
            lastLoginAt = 1_000L,
        ),
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): SmsTestStack {
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
        val readStateStore = NotifyReadStateStore(dataStore)
        val notifyRepo = DefaultNotifyRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
            readStateStore = readStateStore,
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )
        val smsRepo = DefaultSmsRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
            notifyRepository = notifyRepo,
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )
        return SmsTestStack(authRepo, notifyRepo, smsRepo)
    }

    @Test
    fun getConversations_realSmsListSample_parsesAll50ConversationsAndSyncsUnreadCount() = runBlocking {
        val stack = createStack()
        val sampleHtml = SampleTestLoader.loadSample("sms/sms_list.html")
        server.enqueue(gbkHtmlResponse(sampleHtml))

        val result = stack.smsRepo.getConversations(unreadOnly = false)
        assertTrue("Expected Success, got $result", result is MessageResult.Success)
        val page = (result as MessageResult.Success).data
        assertTrue(page.hasContainer)
        assertEquals(50, page.items.size)
        assertEquals("20000", page.items.first().uid)
        assertEquals("HiPeer", page.items.first().author)
        assertEquals(0, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(0, stack.notifyRepo.notifyUnreadCount.value)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/forum/pm.php?filter=privatepm", request.target)
    }

    @Test
    fun getConversations_unreadOnlyAndWithUnreadMarkers_syncsSmsUnreadWithoutTouchingNotifyUnread() = runBlocking {
        val stack = createStack()
        val privatePmHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <div class="promptcontent">
                <a id="prompt_pm">私人消息 (0)</a>
                <a id="prompt_threads">帖子消息 (3)</a>
              </div>
              <ul class="pm_list">
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 10:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">未读一</div>
                </li>
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20002">Bob</a></cite> 2026-11-25 09:00</p>
                  <div class="summary">已读会话</div>
                </li>
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20003">Carol</a></cite> 2026-11-25 08:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">未读二</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(privatePmHtml))

        val privateResult = stack.smsRepo.getConversations(unreadOnly = false)
        assertTrue(privateResult is MessageResult.Success)
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)
        assertEquals(3, stack.notifyRepo.notifyUnreadCount.value)
        assertEquals("/forum/pm.php?filter=privatepm", server.takeRequest().target)

        // unreadOnly = true hits pm.php?filter=newpm where every listed item is unread
        val newPmHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <ul class="pm_list">
                <li>
                  <p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 10:00</p>
                  <div class="summary">唯一未读</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(newPmHtml))

        val newOnlyResult = stack.smsRepo.getConversations(unreadOnly = true)
        assertTrue(newOnlyResult is MessageResult.Success)
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals("/forum/pm.php?filter=newpm", server.takeRequest().target)
    }

    @Test
    fun getConversationDetail_realSmsDetailSample_derivesAvatarBaseUrlAndMarksConversationRead() = runBlocking {
        val stack = createStack()
        // Seed 2 unread conversations first (20000 and 20001)
        stack.notifyRepo.updateSmsUnreadState(setOf("20000", "20001"))
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)

        val detailSampleHtml = SampleTestLoader.loadSample("sms/sms_detail.html")
        server.enqueue(gbkHtmlResponse(detailSampleHtml))

        val result = stack.smsRepo.getConversationDetail(uid = "20000")
        assertTrue("Expected Success, got $result", result is MessageResult.Success)
        val page = (result as MessageResult.Success).data
        assertTrue(page.hasContainer)
        assertEquals(9, page.messages.size)
        assertEquals("00000000", page.formhash)

        val expectedPrefix = "${server.url("").toString().trimEnd('/')}/forum/uc_server/data/avatar/"
        assertEquals(
            "${expectedPrefix}000/01/00/00_avatar_middle.jpg",
            page.messages.first().avatarUrl,
        )
        assertEquals(
            "${expectedPrefix}000/02/00/00_avatar_middle.jpg",
            page.messages[1].avatarUrl,
        )

        // Opening 20000 marks it read -> unread count drops from 2 to 1; reopening stays 1
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals("/forum/pm.php?daterange=5&uid=20000", server.takeRequest().target)

        server.enqueue(gbkHtmlResponse(detailSampleHtml))
        stack.smsRepo.getConversationDetail(uid = "20000")
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
    }

    @Test
    fun sendSms_byUid_prefetchesFormhashPostsGbkWithChineseAndEmojiWithoutSpuriousReloginAndStartsCooldown() = runBlocking {
        val stack = createStack()
        val loginHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> gbkHtmlResponse(
                    MessageTestFixtures.smsPrefetchHtml(formhash = "fh_sms_01"),
                )
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> gbkXmlResponse(
                    """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">你好世界😀</div></li>]]></root>""",
                )
                else -> {
                    if (request.target.startsWith("/forum/logging.php")) loginHits.incrementAndGet()
                    MockResponse.Builder().code(404).build()
                }
            }
        }

        val outcome = stack.smsRepo.sendSms(
            uid = "20000",
            username = null,
            message = "你好世界😀",
        )

        assertEquals(SmsSendOutcome.Sent, outcome)
        assertEquals("inajax=1 success XML must NOT trigger spurious silent relogin", 0, loginHits.get())
        assertEquals(2, server.requestCount)
        assertEquals(virtualClockMs.get() + 15_000L, stack.smsRepo.cooldownUntilMs.value)
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())

        val prefetchReq = server.takeRequest()
        assertEquals("GET", prefetchReq.method)
        assertEquals("/forum/pm.php?daterange=1&uid=20000", prefetchReq.target)

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000", postReq.target)
        assertTrue(postReq.headers["Content-Type"].orEmpty().contains("GBK", ignoreCase = true))
        val fields = decodeGbkForm(postReq.body?.utf8().orEmpty())
        assertEquals("fh_sms_01", fields["formhash"])
        assertEquals(virtualClockMs.get().toString(), fields["lastdaterange"])
        assertEquals("pmreply", fields["handlekey"])
        assertEquals("你好世界&#128512;", fields["message"])
        assertFalse("msgto must not be sent when uid is provided", fields.containsKey("msgto"))
    }

    @Test
    fun sendSms_byUsername_prefetchesWithEmptyUidAndPostsMsgtoField() = runBlocking {
        val stack = createStack()
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_by_name")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">已发送</div>]]></root>""",
            ),
        )

        val outcome = stack.smsRepo.sendSms(
            uid = null,
            username = "张三",
            message = "按用户名发送",
        )

        assertEquals(SmsSendOutcome.Sent, outcome)
        assertEquals("/forum/pm.php?daterange=1&uid=", server.takeRequest().target)
        val postReq = server.takeRequest()
        assertEquals("/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1", postReq.target)
        val fields = decodeGbkForm(postReq.body?.utf8().orEmpty())
        assertEquals("fh_by_name", fields["formhash"])
        assertEquals("pmreply", fields["handlekey"])
        assertEquals("张三", fields["msgto"])
        assertEquals("按用户名发送", fields["message"])
    }

    @Test
    fun sendSms_ajaxSessionExpired_reusesSingleSilentReloginViaCustomPredicateAndSucceedsOnRetry() = runBlocking {
        val stack = createStack()
        val postHits = AtomicInteger(0)
        val loginPostHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> gbkHtmlResponse(
                    MessageTestFixtures.smsPrefetchHtml(formhash = "fh_relogin_sms"),
                )
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> {
                    postHits.incrementAndGet()
                    if (request.headers["Cookie"].orEmpty().contains("cdb_auth=sms_fresh_auth")) {
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">重登后发送成功</div>]]></root>""",
                        )
                    } else {
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您还未登录，无法进行此操作]]></root>""",
                        )
                    }
                }
                "/forum/logging.php?action=login" -> gbkHtmlResponse(
                    MessageTestFixtures.loginFormHtml("fh_login_ajax"),
                )
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    loginPostHits.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=sms_fresh_auth; Path=/"),
                    )
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val outcome = stack.smsRepo.sendSms(uid = "20000", username = null, message = "会话边界测试")
        assertEquals(SmsSendOutcome.Sent, outcome)
        assertEquals("POST must execute twice (initial + 1 retry after relogin)", 2, postHits.get())
        assertEquals("Single silent relogin POST expected", 1, loginPostHits.get())
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun sendSms_successEchoContainingUnauthText_neverTriggersSpuriousReloginOrDuplicateSend() = runBlocking {
        val stack = createStack()
        val postHits = AtomicInteger(0)
        val loginHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> gbkHtmlResponse(
                    MessageTestFixtures.smsPrefetchHtml(formhash = "fh_echo"),
                )
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> {
                    postHits.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">提示：您还未登录</div></li>]]></root>""",
                    )
                }
                else -> {
                    if (request.target.startsWith("/forum/logging.php")) loginHits.incrementAndGet()
                    MockResponse.Builder().code(404).build()
                }
            }
        }

        val outcome = stack.smsRepo.sendSms(uid = "20000", username = null, message = "提示：您还未登录")
        assertEquals(SmsSendOutcome.Sent, outcome)
        assertEquals(1, postHits.get())
        assertEquals(0, loginHits.get())
    }

    @Test
    fun sendSms_fifteenSecondCooldown_virtualClockCeilsRemainingSecondsBlocksWithZeroRequestsAndAllowsAt15000ms() = runBlocking {
        val stack = createStack()
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_1")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">ok1</div>]]></root>"""))

        val first = stack.smsRepo.sendSms(uid = "20000", username = null, message = "第一条")
        assertEquals(SmsSendOutcome.Sent, first)
        assertEquals(2, server.requestCount)

        // +0ms: 15_000ms remaining -> ceil = 15s, 0 extra network requests
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
        assertEquals(
            SmsSendOutcome.CooldownActive(15),
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "第二条"),
        )
        assertEquals(2, server.requestCount)

        // +1ms: 14_999ms remaining -> ceil = 15s
        virtualClockMs.addAndGet(1L)
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
        assertEquals(
            SmsSendOutcome.CooldownActive(15),
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "第二条"),
        )
        assertEquals(2, server.requestCount)

        // +14_001ms total: 999ms remaining -> ceil = 1s, still blocked with 0 extra network requests
        virtualClockMs.addAndGet(14_000L)
        assertEquals(1, stack.smsRepo.getRemainingCooldownSeconds())
        assertEquals(
            SmsSendOutcome.CooldownActive(1),
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "第二条"),
        )
        assertEquals(2, server.requestCount)

        // +14_999ms total: 1ms remaining -> ceil = 1s
        virtualClockMs.addAndGet(998L)
        assertEquals(1, stack.smsRepo.getRemainingCooldownSeconds())
        assertEquals(
            SmsSendOutcome.CooldownActive(1),
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "第二条"),
        )
        assertEquals(2, server.requestCount)

        // +15_000ms total: 0ms remaining -> allowed!
        virtualClockMs.addAndGet(1L)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())

        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_2")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">ok2</div>]]></root>"""))
        val second = stack.smsRepo.sendSms(uid = "20000", username = null, message = "第二条")
        assertEquals(SmsSendOutcome.Sent, second)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun sendSms_serverRejectedOrNetworkError_doesNotStartCooldownAllowingImmediateRetry() = runBlocking {
        val stack = createStack()

        // 1. Server business rejection in XML -> Rejected, cooldown remains 0
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_rej")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[收件人不存在<script>x()</script>]]></root>""",
            ),
        )
        val rejected = stack.smsRepo.sendSms(uid = null, username = "Ghost", message = "你好")
        assertEquals(SmsSendOutcome.Rejected("收件人不存在"), rejected)
        assertEquals(0L, stack.smsRepo.cooldownUntilMs.value)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())

        // 2. HTTP 500 -> Error, cooldown still 0
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_err")))
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())
        val errored = stack.smsRepo.sendSms(uid = "20000", username = null, message = "重试一")
        assertTrue("Expected Error, got $errored", errored is SmsSendOutcome.Error)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())

        // 3. Immediate retry at same virtual timestamp succeeds!
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_ok")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">重发成功</div>]]></root>"""))
        val retried = stack.smsRepo.sendSms(uid = "20000", username = null, message = "重试二")
        assertEquals(SmsSendOutcome.Sent, retried)
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun sendSms_missingFormhashInPrefetch_returnsFormhashMissingAndSkipsPost() = runBlocking {
        val stack = createStack()
        // Logged-in page with #umenu logout link formhash, but NO form input#formhash / input[name=formhash]!
        val missingFormInputHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu">
                <cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=logout_only">退出</a>
              </div>
              <div id="content">无发信表单</div>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(missingFormInputHtml))

        val outcome = stack.smsRepo.sendSms(uid = "20000", username = null, message = "测试")
        assertEquals(SmsSendOutcome.FormhashMissing, outcome)
        assertEquals("Must NOT send POST when prefetch lacks form input formhash", 1, server.requestCount)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun sendSms_blankMessageOrMissingRecipient_rejectedWithZeroRequestsAndNoCooldown() = runBlocking {
        val stack = createStack()

        val blankMsg = stack.smsRepo.sendSms(uid = "20000", username = null, message = "   ")
        assertTrue(blankMsg is SmsSendOutcome.Rejected)

        val missingRecipient = stack.smsRepo.sendSms(uid = "  ", username = "", message = "hello")
        assertTrue(missingRecipient is SmsSendOutcome.Rejected)

        assertEquals(0, server.requestCount)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun sendSms_concurrentCalls_serializedByMutexSecondBlockedByCooldownWithZeroExtraRequests() = runBlocking {
        val stack = createStack()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_mutex"))
                }
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> {
                    gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">ok</div>]]></root>""")
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val call1 = async(Dispatchers.Default) {
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "并发1")
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val call2 = async(Dispatchers.Default) {
            stack.smsRepo.sendSms(uid = "20000", username = null, message = "并发2")
        }
        releaseFirst.countDown()

        val r1 = withTimeout(5.seconds) { call1.await() }
        val r2 = withTimeout(5.seconds) { call2.await() }
        assertEquals(SmsSendOutcome.Sent, r1)
        assertEquals(SmsSendOutcome.CooldownActive(15), r2)
        assertEquals("Only the first call should hit the network (GET + POST)", 2, server.requestCount)
    }

    @Test
    fun clearConversation_callsDelEndpointChecksMetaErrorAndUpdatesUnreadSmsCount() = runBlocking {
        val stack = createStack()
        stack.notifyRepo.updateSmsUnreadState(setOf("20000", "30000"))
        assertEquals(2, stack.notifyRepo.smsUnreadCount.value)

        // 1. Success path
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loggedInHtml("10000", "HiUser", "操作完成")))
        val cleared = stack.smsRepo.clearConversation("20000")
        assertEquals(SmsClearOutcome.Cleared, cleared)
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)
        assertEquals("/forum/pm.php?action=del&uid=20000&filter=privatepm", server.takeRequest().target)

        // 2. Server meta.errorMessage -> Rejected
        val errorHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <div class="alert_error">无法删除该对话</div>
            </body></html>
        """.trimIndent()
        server.enqueue(gbkHtmlResponse(errorHtml))
        val rejected = stack.smsRepo.clearConversation("30000")
        assertEquals(SmsClearOutcome.Rejected("无法删除该对话"), rejected)
        assertEquals(1, stack.notifyRepo.smsUnreadCount.value)

        // 3. HTTP 500 -> Error
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val errored = stack.smsRepo.clearConversation("30000")
        assertTrue(errored is SmsClearOutcome.Error)
        assertNotNull((errored as SmsClearOutcome.Error).cause)
    }

    @Test
    fun sendSms_manualLogoutAuthExpired_returnsAuthRequiredWithoutStartingCooldown() = runBlocking {
        val stack = createStack()
        stack.authRepo.logout()

        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loggedOutHtml("请先登录")))
        val outcome = stack.smsRepo.sendSms(uid = "20000", username = null, message = "退出后发送")
        assertEquals(SmsSendOutcome.AuthRequired(AuthRequiredReason.MANUALLY_LOGGED_OUT), outcome)
        assertEquals(1, server.requestCount)
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    @Test
    fun sendSms_staleNullCollectorEmissionAfterColdStartHydration_doesNotClearActiveCooldown() = runBlocking {
        val account = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("secret_pw"), lastLoginAt = 1_000L)
        val backing = MutableStateFlow<SavedAccount?>(null)
        val collectorCapturedNull = CountDownLatch(1)
        val releaseStaleNull = CountDownLatch(1)
        val staleNullDelivered = CountDownLatch(1)

        val racingFlow = object : StateFlow<SavedAccount?> {
            override val value: SavedAccount? get() = backing.value
            override val replayCache: List<SavedAccount?> get() = backing.replayCache
            override suspend fun collect(collector: FlowCollector<SavedAccount?>): Nothing {
                val capturedInitial = backing.value
                collectorCapturedNull.countDown()
                releaseStaleNull.await(5, TimeUnit.SECONDS)
                collector.emit(capturedInitial)
                staleNullDelivered.countDown()
                backing.collect(collector)
            }
        }

        val fakeAuth = object : AuthRepository {
            override val currentAccount: StateFlow<SavedAccount?> = racingFlow
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOf(account))
            override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn(account))
            override val sessionEpoch: Long = 1L
            override suspend fun login(
                username: String,
                passwordOrMd5: String,
                securityQuestionId: Int,
                securityAnswer: String,
            ) = error("unused")
            override suspend fun switchAccount(uid: String) = error("unused")
            override suspend fun logout() = Unit
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = error("unused")
        }

        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val primaryGateway = SiteAccessGateway(
            client = primaryClient,
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
            clock = { virtualClockMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val forumClient = ForumHttpClient(primaryGateway, settingsRepository, fakeAuth)
        val storeFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { storeFile },
        )
        val notifyRepo = DefaultNotifyRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = fakeAuth,
            readStateStore = NotifyReadStateStore(dataStore),
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )
        val smsRepo = DefaultSmsRepository(
            forumHttpClient = forumClient,
            settingsRepository = settingsRepository,
            authRepository = fakeAuth,
            notifyRepository = notifyRepo,
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )

        assertTrue(collectorCapturedNull.await(5, TimeUnit.SECONDS))
        backing.value = account

        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_stale")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">ok</div>]]></root>"""))
        assertEquals(SmsSendOutcome.Sent, smsRepo.sendSms(uid = "20000", username = null, message = "hi"))
        assertEquals(15, smsRepo.getRemainingCooldownSeconds())

        // Release lagging cold-start null emission after cooldown was armed
        releaseStaleNull.countDown()
        assertTrue(staleNullDelivered.await(5, TimeUnit.SECONDS))
        assertEquals(15, smsRepo.getRemainingCooldownSeconds())
        assertEquals(SmsSendOutcome.CooldownActive(15), smsRepo.sendSms(uid = "20000", username = null, message = "hi2"))
    }

    @Test
    fun rD7S1_sendSms_loggedInGroupRestrictedNoPerm_returnsRejectedWithZeroReloginAndZeroRetry() = runBlocking {
        val stack = createStack()
        val postHits = AtomicInteger(0)
        val loginHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> gbkHtmlResponse(
                    MessageTestFixtures.smsPrefetchHtml(formhash = "fh_noperm"),
                )
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> {
                    val attempt = postHits.incrementAndGet()
                    if (attempt == 1) {
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您无权进行当前操作，您已经登录，但您的帐号或其所在的用户组无权访问当前页面。]]></root>""",
                        )
                    } else {
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您无权进行当前操作。]]></root>""",
                        )
                    }
                }
                else -> {
                    if (request.target.startsWith("/forum/logging.php")) loginHits.incrementAndGet()
                    MockResponse.Builder().code(404).build()
                }
            }
        }

        // Case 1: Full Discuz! logged-in group-restricted NOPERM containing both "无权进行当前操作" and "您已经登录"
        val fullNoPerm = stack.smsRepo.sendSms(uid = "20000", username = null, message = "组受限测试1")
        assertTrue("Expected Rejected, got $fullNoPerm", fullNoPerm is SmsSendOutcome.Rejected)
        assertTrue((fullNoPerm as SmsSendOutcome.Rejected).message.contains("无权进行当前操作"))
        assertEquals("Logged-in group NOPERM must NOT trigger silent relogin", 0, loginHits.get())
        assertEquals("Logged-in group NOPERM must NOT retry POST", 1, postHits.get())
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())

        // Case 2: Shortened NOPERM containing only "无权进行当前操作" without unauthenticated login prompt
        val shortNoPerm = stack.smsRepo.sendSms(uid = "20000", username = null, message = "组受限测试2")
        assertTrue("Expected Rejected, got $shortNoPerm", shortNoPerm is SmsSendOutcome.Rejected)
        assertEquals("Short NOPERM must NOT trigger silent relogin", 0, loginHits.get())
        assertEquals("Short NOPERM must NOT retry POST", 2, postHits.get())
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun rD7S4a_accountSwitchAndLogout_immediatelyResetFifteenSecondCooldown() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 2_000L)
        val accountB = SavedAccount("10002", "Bob", computeLegacyPasswordMd5("pwB"), lastLoginAt = 1_000L)
        val stack = createStack(seedLoggedInAccount = accountA)
        credentialsStore.upsert(accountB)
        withTimeout(3.seconds) {
            stack.authRepo.currentAccount.first { it?.uid == accountA.uid }
        }

        // 1. Account A sends an SMS -> 15s cooldown starts
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_a", uid = "10001", username = "Alice")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">from A</div>]]></root>"""))
        assertEquals(SmsSendOutcome.Sent, stack.smsRepo.sendSms(uid = "20000", username = null, message = "hi from A"))
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
        assertTrue(stack.smsRepo.cooldownUntilMs.value > 0L)

        // 2. Switch to Account B -> reactive observer immediately clears cooldown to 0
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.loginFormHtml("fh_switch_b")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Bob]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=bob_cookie; Path=/"),
            ),
        )
        stack.authRepo.switchAccount(accountB.uid)
        withTimeout(3.seconds) {
            stack.smsRepo.cooldownUntilMs.first { it == 0L }
        }
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())

        // 3. Account B can immediately send at the same virtual timestamp (no leftover cooldown from A)
        server.enqueue(gbkHtmlResponse(MessageTestFixtures.smsPrefetchHtml(formhash = "fh_b", uid = "10002", username = "Bob")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">from B</div>]]></root>"""))
        assertEquals(SmsSendOutcome.Sent, stack.smsRepo.sendSms(uid = "20000", username = null, message = "hi from B"))
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())

        // 4. Logout immediately clears cooldown to 0 without needing another sendSms/getRemainingCooldownSeconds call
        stack.authRepo.logout()
        withTimeout(3.seconds) {
            stack.smsRepo.cooldownUntilMs.first { it == 0L }
        }
        assertEquals(0, stack.smsRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun rD7S4c_sendSms_reloginRetrySuccessEchoingUnauthMarker_shortCircuitsToSentWithoutAuthRequired() = runBlocking {
        val stack = createStack()
        val postHits = AtomicInteger(0)
        val loginPostHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?daterange=1&uid=20000" -> gbkHtmlResponse(
                    MessageTestFixtures.smsPrefetchHtml(formhash = "fh_relogin_echo"),
                )
                "/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=20000" -> {
                    postHits.incrementAndGet()
                    if (request.headers["Cookie"].orEmpty().contains("cdb_auth=fresh_echo_auth")) {
                        // Retry succeeds and echoes the user's message containing "您还未登录" inside div.summary!
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">正文引用：您还未登录，请先登录</div></li>]]></root>""",
                        )
                    } else {
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您还未登录，无法进行此操作]]></root>""",
                        )
                    }
                }
                "/forum/logging.php?action=login" -> gbkHtmlResponse(
                    MessageTestFixtures.loginFormHtml("fh_login_echo"),
                )
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    loginPostHits.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=fresh_echo_auth; Path=/"),
                    )
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val outcome = stack.smsRepo.sendSms(
            uid = "20000",
            username = null,
            message = "正文引用：您还未登录，请先登录",
        )
        assertEquals(
            "Post-relogin retry success echoing '您还未登录' must short-circuit to Sent (ForumHttpClient.kt:96)",
            SmsSendOutcome.Sent,
            outcome,
        )
        assertEquals(2, postHits.get())
        assertEquals(1, loginPostHits.get())
        assertEquals(15, stack.smsRepo.getRemainingCooldownSeconds())
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal object MessageTestFixtures {
    private val GBK: Charset = Charset.forName("GBK")

    fun gbkBuffer(text: String): Buffer = Buffer().write(text.toByteArray(GBK))

    fun loginFormHtml(formhash: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
            <input type="hidden" name="formhash" value="$formhash" />
          </form>
        </body>
        </html>
    """.trimIndent()

    fun loggedInHtml(uid: String, username: String, body: String = ""): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu">
            <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
            <a href="logging.php?action=logout&amp;formhash=logoutfh">退出</a>
          </div>
          <div id="content">$body</div>
        </body>
        </html>
    """.trimIndent()

    fun loggedOutHtml(message: String = "请先登录"): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <div class="alert_error">$message</div>
        </body>
        </html>
    """.trimIndent()

    fun smsPrefetchHtml(formhash: String, uid: String = "10000", username: String = "HiUser"): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite></div>
          <form id="pmform" method="post" action="pm.php?action=send&amp;pmsubmit=yes&amp;infloat=yes">
            <input type="hidden" name="formhash" id="formhash" value="$formhash" />
            <input type="hidden" name="handlekey" value="pmreply" />
          </form>
        </body>
        </html>
    """.trimIndent()
}

internal fun gbkHtmlResponse(
    html: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(MessageTestFixtures.gbkBuffer(html))
    .build()

internal fun gbkXmlResponse(
    xml: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/xml; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(MessageTestFixtures.gbkBuffer(xml))
    .build()

internal fun decodeGbkForm(rawBody: String): Map<String, String> {
    if (rawBody.isBlank()) return emptyMap()
    return rawBody.split('&').associate { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) {
            URLDecoder.decode(pair, "GBK") to ""
        } else {
            URLDecoder.decode(pair.substring(0, idx), "GBK") to
                URLDecoder.decode(pair.substring(idx + 1), "GBK")
        }
    }
}

internal class MessageTestCredentialsStore : CredentialsStore {
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

internal class MessageTestSettingsRepository(
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
