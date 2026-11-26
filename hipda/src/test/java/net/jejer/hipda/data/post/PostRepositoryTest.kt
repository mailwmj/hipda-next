package net.jejer.hipda.data.post

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dagger.Binds
import dagger.Module
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.observe
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.di.PostModule
import net.jejer.hipda.parser.SampleTestLoader
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.post.PrePostInfo
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PostRepositoryTest {

    private lateinit var virtualClockMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: PostTestCredentialsStore
    private lateinit var settingsRepository: PostTestSettingsRepository
    private lateinit var database: HiDatabase
    private lateinit var draftDao: DraftDao
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        credentialsStore = PostTestCredentialsStore()
        database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        draftDao = database.draftDao()
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
        database.close()
        server.close()
    }

    private data class PostTestStack(
        val authRepo: DefaultAuthRepository,
        val postRepo: DefaultPostRepository,
        val primaryGateway: SiteAccessGateway,
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
    ): PostTestStack {
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
        val postRepo = DefaultPostRepository(
            forumHttpClient = forumClient,
            authRepository = authRepo,
            settingsRepository = settingsRepository,
            draftDao = draftDao,
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )
        return PostTestStack(
            authRepo = authRepo,
            postRepo = postRepo,
            primaryGateway = primaryGateway,
        )
    }

    @Test
    fun fetchPrePost_realNewThreadSampleParsesFormFieldsUploadLimitAndOrderedTypes() = runBlocking {
        val stack = createStack()
        val sampleHtml = SampleTestLoader.loadSample("post_form/newthread_fid2.html")
        server.enqueue(gbkHtmlResponse(sampleHtml))

        val result = stack.postRepo.fetchPrePost(PrePostRequest.NewThread(fid = 2))
        assertTrue("Expected Success but got $result", result is PrePostResult.Success)
        val info = (result as PrePostResult.Success).info

        assertEquals("00000000", info.formhash)
        assertEquals("10000", info.uid)
        assertEquals("00000000000000000000000000000000", info.hash)
        assertEquals(8 * 1024 * 1024, info.maxUploadFileSizeBytes)
        assertEquals("0", info.typeId)
        assertEquals(16, info.typeOptions.size)
        assertEquals("分类", info.typeOptions["0"])
        assertEquals("Discovery", info.typeOptions["56"])
        assertEquals("站务", info.typeOptions.entries.last().value)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/forum/post.php?action=newthread&fid=2", recorded.target)
    }

    @Test
    fun fetchPrePost_realNameBindRedirectReturnsRealNameBindRequiredWithoutRelogin() = runBlocking {
        val stack = createStack()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/memcp.php?action=bind")
                .build(),
        )
        // Minimal bind page even without #umenu should still be classified as RealNameBindRequired
        server.enqueue(gbkHtmlResponse("<html><body>请先绑定手机号</body></html>"))

        val result = stack.postRepo.fetchPrePost(PrePostRequest.NewThread(fid = 2))
        assertEquals(PrePostResult.RealNameBindRequired, result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fetchPrePost_andEditSubmit_roundTripEmojiDecodesNumericEntitiesAndReencodesGbkDecimal() = runBlocking {
        val stack = createStack()
        val editPrePostHtml = PostTestFixtures.prePostHtml(
            formhash = "editfh01",
            subject = "标题 &amp;#128512; &#128513; &amp;#x1F602; A &amp; B",
            textarea = "正文 &amp;#128512; &#x1F600; [b]粗体[/b] A &amp; B &amp;#0; &amp;#55296;",
            isDeletable = true,
            typeOptions = listOf("0" to "分类", "56" to "Discovery"),
            selectedTypeId = "56",
            existingImages = listOf("9001"),
        )
        server.enqueue(gbkHtmlResponse(editPrePostHtml))

        val prePost = stack.postRepo.fetchPrePost(
            PrePostRequest.EditPost(fid = 2, tid = "2602293", pid = "53529001", page = 2),
        )
        assertTrue("Expected Success but got $prePost", prePost is PrePostResult.Success)
        val info = (prePost as PrePostResult.Success).info

        // R-B9b S3: both double-escaped &amp;#...; and single &#...; / &#x...; become real Unicode Emoji
        assertEquals("标题 \uD83D\uDE00 \uD83D\uDE01 \uD83D\uDE02 A & B", info.subject)
        assertEquals(
            "正文 \uD83D\uDE00 \uD83D\uDE00 [b]粗体[/b] A & B &#0; &#55296;",
            info.text,
        )
        assertTrue(info.isDeletable)
        assertEquals("56", info.typeId)
        assertEquals(listOf("9001"), info.existingImages)

        val getReq = server.takeRequest()
        assertEquals("/forum/post.php?action=edit&fid=2&tid=2602293&pid=53529001&page=2", getReq.target)

        // Submit edited subject + text with supplementary Emoji + GB18030-only CJK U+20BB7 (𠮷)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&page=2")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser", "<div id=\"postlist\"></div>")))

        val updatedText = "${info.text} 新增\uD842\uDFB7"
        val submitResult = stack.postRepo.submitPost(
            PostSubmitRequest.EditPost(
                fid = 2,
                tid = "2602293",
                pid = "53529001",
                page = 2,
                subject = info.subject,
                typeId = info.typeId,
                content = updatedText,
                prePostInfo = info,
            ),
        )
        assertTrue("Expected Success but got $submitResult", submitResult is PostSubmitResult.Success)

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals(
            "/forum/post.php?action=edit&extra=&editsubmit=yes&mod=&fid=2&tid=2602293&pid=53529001&page=2",
            postReq.target,
        )
        assertEquals("application/x-www-form-urlencoded; charset=GBK", postReq.headers["Content-Type"])

        val rawBody = postReq.body!!.utf8()
        // Byte-level assertion: 😀 -> &#128512; -> %26%23128512%3B, 𠮷 -> &#134071; -> %26%23134071%3B
        assertTrue("Raw GBK form body should encode 😀 as decimal entity: $rawBody", rawBody.contains("%26%23128512%3B"))
        assertTrue("Raw GBK form body should encode 😁 as decimal entity: $rawBody", rawBody.contains("%26%23128513%3B"))
        assertTrue("Raw GBK form body should encode 😂 as decimal entity: $rawBody", rawBody.contains("%26%23128514%3B"))
        assertTrue("Raw GBK form body should encode 𠮷 as decimal entity: $rawBody", rawBody.contains("%26%23134071%3B"))

        val decodedFields = decodeGbkFormPairs(rawBody).toMap()
        assertEquals("标题 &#128512; &#128513; &#128514; A & B", decodedFields["subject"])
        assertEquals(
            "正文 &#128512; &#128512; [b]粗体[/b] A & B &#0; &#55296; 新增&#134071;",
            decodedFields["message"],
        )
        assertEquals("56", decodedFields["typeid"])
        assertNull(decodedFields["delete"])
    }

    @Test
    fun quotePost_decodesQuoteTextEmojiAndReencodesEvenWhenCallerPassesRawPrePostInfo() = runBlocking {
        val stack = createStack()
        val quoteHtml = PostTestFixtures.prePostHtml(
            formhash = "quotefh1",
            textarea = "[quote]原帖 &amp;#128512; &#x1F680;[/quote]",
            noticeAuthor = "enc_author_token",
            noticeAuthorMsg = "原帖摘要",
            noticeTrimStr = "[b]回复 [url=http://example.com]1#[/url] [i]Author[/i] [/b]",
        )
        server.enqueue(gbkHtmlResponse(quoteHtml))
        val prePost = stack.postRepo.fetchPrePost(PrePostRequest.QuotePost(tid = "100", pid = "201"))
        assertTrue(prePost is PrePostResult.Success)
        val decodedInfo = (prePost as PrePostResult.Success).info
        assertEquals("[quote]原帖 \uD83D\uDE00 \uD83D\uDE80[/quote]", decodedInfo.quoteText)
        assertEquals("", decodedInfo.text)
        assertEquals("enc_author_token", decodedInfo.noticeAuthor)
        assertEquals("/forum/post.php?action=reply&tid=100&repquote=201", server.takeRequest().target)

        // Simulate caller passing an undecoded PrePostInfo directly (contains raw &amp;#128512; and &#x1F680;)
        val rawInfo = decodedInfo.copy(quoteText = "[quote]原帖 &amp;#128512; &#x1F680;[/quote]")
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=100&extra=#pid300")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val result = stack.postRepo.submitPost(
            PostSubmitRequest.QuotePost(
                tid = "100",
                pid = "201",
                content = "同意\uD83D\uDC4D",
                prePostInfo = rawInfo,
            ),
        )
        assertTrue(result is PostSubmitResult.Success)

        val postReq = server.takeRequest()
        assertEquals("/forum/post.php?action=reply&tid=100&replysubmit=yes", postReq.target)
        val fields = decodeGbkFormPairs(postReq.body!!.utf8()).toMap()
        assertEquals(
            "[quote]原帖 &#128512; &#128640;[/quote]\n\n    同意&#128077;",
            fields["message"],
        )
        assertEquals("enc_author_token", fields["noticeauthor"])
        assertEquals("原帖摘要", fields["noticeauthormsg"])
        assertEquals("[b]回复 [url=http://example.com]1#[/url] [i]Author[/i] [/b]", fields["noticetrimstr"])
    }

    @Test
    fun submitPost_newThreadSendsExpectedFieldsAttachmentsTailAndParsesRealThreadDetailRedirect() = runBlocking {
        val stack = createStack()
        settingsRepository.setTailEnabled(true)
        settingsRepository.setTailText("发自 HiPDA")
        settingsRepository.setTailUrl("www.4d4y.com/forum/viewthread.php?tid=1")

        val realThreadHtml = SampleTestLoader.loadSample("thread_detail/thread_p1.html")
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&extra=")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(realThreadHtml))

        val prePostInfo = minimalPrePostInfo(formhash = "newfh123")
        val result = stack.postRepo.submitPost(
            PostSubmitRequest.NewThread(
                fid = 2,
                subject = "新帖标题测试",
                content = "正文看这里 https://example.com/a?x=1 和 [url=https://keep.me]链接[/url] 与图 [attachimg]1002[/attachimg] 版本 v1.2.3",
                typeId = "56",
                newAttachments = listOf("1001", "1002", "1003"),
                deletedAttachments = listOf("888"),
                prePostInfo = prePostInfo,
            ),
        )
        assertTrue("Expected Success but got $result", result is PostSubmitResult.Success)
        val success = result as PostSubmitResult.Success
        assertEquals("2602293", success.tid)
        assertFalse(success.threadDeleted)
        assertNotNull(success.redirectedPage)
        assertEquals(50, success.redirectedPage!!.floors.size)

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/forum/post.php?action=newthread&fid=2&typeid=56&topicsubmit=yes", postReq.target)

        val pairs = decodeGbkFormPairs(postReq.body!!.utf8())
        val map = pairs.toMap()
        assertEquals("newfh123", map["formhash"])
        assertEquals(virtualClockMs.get().toString(), map["posttime"])
        assertEquals("0", map["wysiwyg"])
        assertEquals("1", map["usesig"])
        assertEquals("新帖标题测试", map["subject"])
        assertEquals("1", map["attention_add"])

        val expectedTail = "[url=https://www.4d4y.com/forum/viewthread.php?tid=1][size=1]发自 HiPDA[/size][/url]"
        val expectedMessage =
            "正文看这里 [url]https://example.com/a?x=1[/url] 和 [url=https://keep.me]链接[/url] 与图 [attachimg]1002[/attachimg] 版本 v1.2.3\n" +
                "[attachimg]1001[/attachimg]\n" +
                "[attachimg]1003[/attachimg]  $expectedTail"
        assertEquals(expectedMessage, map["message"])

        assertEquals(
            listOf("1001", "1002", "1003"),
            pairs.filter { it.first == "attachnew[][description]" }.map { it.second },
        )
        assertEquals(
            listOf("888"),
            pairs.filter { it.first == "attachdel[]" }.map { it.second },
        )
    }

    @Test
    fun submitPost_replyThreadAndReplyPostAutoPrefetchOnceAndSendExpectedFields() = runBlocking {
        val stack = createStack()
        settingsRepository.setTailEnabled(true)
        settingsRepository.setTailText("纯文本尾巴")
        settingsRepository.setTailUrl("")

        // 1) ReplyThread without prePostInfo -> auto prefetch GET then POST
        server.enqueue(gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "replyfh1")))
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/redirect.php?goto=findpost&ptid=500&pid=900")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val replyThreadRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(
                tid = "500",
                content = "快速回复内容 www.example.com/test",
                quickReply = true,
            ),
        )
        assertTrue(replyThreadRes is PostSubmitResult.Success)
        assertEquals("500", (replyThreadRes as PostSubmitResult.Success).tid)

        val get1 = server.takeRequest()
        assertEquals("GET", get1.method)
        assertEquals("/forum/post.php?action=reply&tid=500", get1.target)
        val post1 = server.takeRequest()
        assertEquals("POST", post1.method)
        assertEquals("/forum/post.php?action=reply&tid=500&replysubmit=yes", post1.target)
        val fields1 = decodeGbkFormPairs(post1.body!!.utf8()).toMap()
        assertEquals("replyfh1", fields1["formhash"])
        assertEquals(
            "快速回复内容 [url]www.example.com/test[/url]  [size=1]纯文本尾巴[/size]",
            fields1["message"],
        )
        // Consume redirected GET
        server.takeRequest()

        // Advance past 30s cooldown before ReplyPost
        virtualClockMs.addAndGet(30_000L)

        // 2) ReplyPost without prePostInfo -> auto prefetch reppost GET then POST with notice fields
        server.enqueue(
            gbkHtmlResponse(
                PostTestFixtures.prePostHtml(
                    formhash = "reppostfh2",
                    textarea = "[b]回复 2# 楼主[/b]",
                    noticeAuthor = "notice_token_2",
                    noticeAuthorMsg = "消息内容",
                    noticeTrimStr = "[b]回复 2# 楼主[/b]",
                ),
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=500")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val replyPostRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyPost(
                tid = "500",
                pid = "600",
                content = "回复楼层正文  [size=1]纯文本尾巴[/size]",
            ),
        )
        assertTrue(replyPostRes is PostSubmitResult.Success)

        val get2 = server.takeRequest()
        assertEquals("/forum/post.php?action=reply&tid=500&reppost=600", get2.target)
        val post2 = server.takeRequest()
        assertEquals("/forum/post.php?action=reply&tid=500&replysubmit=yes", post2.target)
        val fields2 = decodeGbkFormPairs(post2.body!!.utf8()).toMap()
        // Tail already present at end -> must not duplicate tail
        assertEquals(
            "[b]回复 2# 楼主[/b]\n\n    回复楼层正文  [size=1]纯文本尾巴[/size]",
            fields2["message"],
        )
        assertEquals("notice_token_2", fields2["noticeauthor"])
        assertEquals("消息内容", fields2["noticeauthormsg"])
        assertEquals("[b]回复 2# 楼主[/b]", fields2["noticetrimstr"])
    }

    @Test
    fun editPostAndDeletePost_neverAppendTailEvenWhenTailEnabled_andDeleteFirstPostSetsThreadDeleted() = runBlocking {
        val stack = createStack()
        settingsRepository.setTailEnabled(true)
        settingsRepository.setTailText("禁止出现在编辑与删除中的尾巴")
        settingsRepository.setTailUrl("example.com")

        // 1) EditPost (non-first floor, no subject/typeId)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=777&page=1")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val editRes = stack.postRepo.submitPost(
            PostSubmitRequest.EditPost(
                fid = 2,
                tid = "777",
                pid = "888",
                content = "修改后的正文内容",
                prePostInfo = minimalPrePostInfo(formhash = "editfh"),
            ),
        )
        assertTrue(editRes is PostSubmitResult.Success)
        val editReq = server.takeRequest()
        val editFields = decodeGbkFormPairs(editReq.body!!.utf8()).toMap()
        assertEquals("修改后的正文内容", editFields["message"])
        assertFalse(editFields["message"]!!.contains("尾巴"))
        assertNull(editFields["subject"])
        assertNull(editFields["typeid"])
        assertNull(editFields["delete"])
        server.takeRequest()

        // 2) DeletePost (first post -> 302 forumdisplay.php -> threadDeleted = true)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/forumdisplay.php?fid=2")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val deleteRes = stack.postRepo.submitPost(
            PostSubmitRequest.DeletePost(
                fid = 2,
                tid = "777",
                pid = "888",
                page = 1,
                content = "原文",
                newAttachments = listOf("9999"),
                prePostInfo = minimalPrePostInfo(formhash = "delfh"),
            ),
        )
        assertTrue(deleteRes is PostSubmitResult.Success)
        val deleteSuccess = deleteRes as PostSubmitResult.Success
        assertEquals("777", deleteSuccess.tid)
        assertTrue(deleteSuccess.threadDeleted)
        assertNull(deleteSuccess.redirectedPage)

        val deleteReq = server.takeRequest()
        assertEquals(
            "/forum/post.php?action=edit&extra=&editsubmit=yes&mod=&fid=2&tid=777&pid=888&page=1",
            deleteReq.target,
        )
        val deleteFields = decodeGbkFormPairs(deleteReq.body!!.utf8()).toMap()
        assertEquals("1", deleteFields["delete"])
        assertEquals("原文", deleteFields["message"])
        assertFalse(deleteFields["message"]!!.contains("尾巴"))
        assertFalse(deleteFields["message"]!!.contains("[attachimg]"))
    }

    @Test
    fun cooldown_virtualClockCeiling_zeroRequestsWhenActive_editBypass_failureDoesNotLock_accountChangeResets() = runBlocking {
        val stack = createStack()
        val info = minimalPrePostInfo(formhash = "coolfh")
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
        assertEquals(0L, stack.postRepo.cooldownUntilMs.value)

        // Failed POST (div.alert_info) must NOT start cooldown
        server.enqueue(
            gbkHtmlResponse(
                PostTestFixtures.loggedInHtml(
                    "10000",
                    "HiUser",
                    "<div class=\"alert_info\"><p>对不起，您的帖子小于 5 个字符</p><a href=\"back\">返回</a></div>",
                ),
            ),
        )
        val failRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "123", content = "hi", prePostInfo = info),
        )
        assertTrue(failRes is PostSubmitResult.SiteError)
        assertEquals("对不起，您的帖子小于 5 个字符", (failRes as PostSubmitResult.SiteError).message)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
        assertEquals(0L, stack.postRepo.cooldownUntilMs.value)

        // Successful Reply starts 30_000ms cooldown
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=123")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val okRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "123", content = "合法回复内容", prePostInfo = info),
        )
        assertTrue(okRes is PostSubmitResult.Success)
        val startMs = virtualClockMs.get()
        assertEquals(startMs + 30_000L, stack.postRepo.cooldownUntilMs.value)
        assertEquals(30, stack.postRepo.getRemainingCooldownSeconds())
        val reqCountAfterReply = server.requestCount

        // At +0ms: blocked with 30s and 0 network requests
        val blockedAt0 = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "123", content = "冷却中回复", prePostInfo = info),
        )
        assertEquals(PostSubmitResult.CooldownActive(30), blockedAt0)
        assertEquals(reqCountAfterReply, server.requestCount)

        // At +29_001ms (999ms remaining): ceiling -> 1s, DeletePost also blocked with 0 requests
        virtualClockMs.set(startMs + 29_001L)
        assertEquals(1, stack.postRepo.getRemainingCooldownSeconds())
        val blockedAt29001 = stack.postRepo.submitPost(
            PostSubmitRequest.DeletePost(fid = 2, tid = "123", pid = "456", prePostInfo = info),
        )
        assertEquals(PostSubmitResult.CooldownActive(1), blockedAt29001)
        assertEquals(reqCountAfterReply, server.requestCount)

        // EditPost during active cooldown MUST NOT be blocked and MUST NOT refresh cooldown
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=123")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))
        val editDuringCooldown = stack.postRepo.submitPost(
            PostSubmitRequest.EditPost(fid = 2, tid = "123", pid = "456", content = "冷却期编辑", prePostInfo = info),
        )
        assertTrue(editDuringCooldown is PostSubmitResult.Success)
        assertEquals(startMs + 30_000L, stack.postRepo.cooldownUntilMs.value)

        // At +30_000ms: cooldown expires -> DeletePost succeeds and starts fresh 30s cooldown
        virtualClockMs.set(startMs + 30_000L)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/forumdisplay.php?fid=2")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))
        val deleteAfterExpiry = stack.postRepo.submitPost(
            PostSubmitRequest.DeletePost(fid = 2, tid = "123", pid = "456", prePostInfo = info),
        )
        assertTrue(deleteAfterExpiry is PostSubmitResult.Success)
        assertEquals(startMs + 60_000L, stack.postRepo.cooldownUntilMs.value)
        assertEquals(30, stack.postRepo.getRemainingCooldownSeconds())

        // Logout resets cooldown immediately
        stack.authRepo.logout()
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
        assertEquals(0L, stack.postRepo.cooldownUntilMs.value)

        // EditPost when no cooldown is active must NOT start cooldown
        credentialsStore.setActive("10000")
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=123")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))
        val editNoCooldown = stack.postRepo.submitPost(
            PostSubmitRequest.EditPost(fid = 2, tid = "123", pid = "456", content = "普通编辑", prePostInfo = info),
        )
        assertTrue(editNoCooldown is PostSubmitResult.Success)
        assertEquals(0L, stack.postRepo.cooldownUntilMs.value)
    }

    @Test
    fun drafts_saveObserveDelete_ignoresBlank_enforcesPositiveDraftKey_andDeletesOnlyOnSuccess() = runBlocking {
        val stack = createStack()
        val info = minimalPrePostInfo(formhash = "draftfh")

        // R-D1: invalid fid / tid / pid rejected by DraftKey
        for (invalid in listOf("", "0", "-1", "abc", "01")) {
            val failed = runCatching { DraftKey.newThread(invalid) }.isFailure
            assertTrue("Expected DraftKey.newThread('$invalid') to fail", failed)
        }

        val replyKey = DraftKey.reply("1001")
        val otherKey = DraftKey.newThread("2")

        // Blank-only draft must not be persisted
        stack.postRepo.saveDraft(key = replyKey, subject = "   ", content = "  \n ")
        stack.postRepo.observeDraft(replyKey).test {
            assertNull(awaitItem())

            stack.postRepo.saveDraft(
                key = replyKey,
                content = "草稿正文内容",
                quotePid = "8801",
                attachments = listOf("101", "  ", "102"),
            )
            val saved = awaitItem()
            assertNotNull(saved)
            assertEquals("草稿正文内容", saved!!.content)
            assertEquals("8801", saved.quotePid)
            assertEquals(listOf("101", "102"), saved.attachments)
            assertEquals(virtualClockMs.get(), saved.updatedAt)
            cancelAndIgnoreRemainingEvents()
        }

        stack.postRepo.saveDraft(key = otherKey, subject = "另一份草稿", content = "其他内容")

        // 1) SiteError failure -> draft MUST be preserved
        server.enqueue(
            gbkHtmlResponse(
                PostTestFixtures.loggedInHtml("10000", "HiUser", "<div class=\"alert_error\">版块已关闭</div>"),
            ),
        )
        val siteError = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(
                tid = "1001",
                content = "草稿正文内容",
                draftKey = replyKey,
                prePostInfo = info,
            ),
        )
        assertTrue(siteError is PostSubmitResult.SiteError)
        draftDao.observe(replyKey).test {
            assertEquals("草稿正文内容", awaitItem()?.content)
            cancelAndIgnoreRemainingEvents()
        }

        // 2) AuthRequired failure (manual logout) -> draft MUST be preserved
        stack.authRepo.logout()
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedOutHtml()))
        val authFail = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(
                tid = "1001",
                content = "草稿正文内容",
                draftKey = replyKey,
                prePostInfo = info,
            ),
        )
        assertEquals(PostSubmitResult.AuthRequired(AuthRequiredReason.MANUALLY_LOGGED_OUT), authFail)
        draftDao.observe(replyKey).test {
            assertEquals("草稿正文内容", awaitItem()?.content)
            cancelAndIgnoreRemainingEvents()
        }

        // Restore active session
        credentialsStore.setActive("10000")

        // 3) Success -> draftKey automatically deleted; unrelated draftKey remains intact
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=1001")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val success = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(
                tid = "1001",
                content = "草稿正文内容",
                draftKey = replyKey,
                prePostInfo = info,
            ),
        )
        assertTrue(success is PostSubmitResult.Success)
        draftDao.observe(replyKey).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        draftDao.observe(otherKey).test {
            assertEquals("另一份草稿", awaitItem()?.subject)
            cancelAndIgnoreRemainingEvents()
        }

        // 4) CooldownActive failure -> otherKey draft MUST be preserved
        val cooldownFail = stack.postRepo.submitPost(
            PostSubmitRequest.NewThread(
                fid = 2,
                subject = "另一份草稿",
                content = "其他内容",
                draftKey = otherKey,
                prePostInfo = info,
            ),
        )
        assertTrue(cooldownFail is PostSubmitResult.CooldownActive)
        draftDao.observe(otherKey).test {
            assertEquals("另一份草稿", awaitItem()?.subject)
            cancelAndIgnoreRemainingEvents()
        }

        // Explicit deleteDraft removes remaining draft
        stack.postRepo.deleteDraft(otherKey)
        draftDao.observe(otherKey).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submitPost_autoPrefetchFailureNeverSendsBlindPost() = runBlocking {
        val stack = createStack()
        server.enqueue(
            gbkHtmlResponse(
                PostTestFixtures.loggedInHtml("10000", "HiUser", "<div class=\"alert_error\">无权在此版块发帖</div>"),
            ),
        )

        val result = stack.postRepo.submitPost(
            PostSubmitRequest.NewThread(
                fid = 2,
                subject = "测试主题",
                content = "测试正文内容",
            ),
        )
        assertTrue(result is PostSubmitResult.SiteError)
        assertEquals("无权在此版块发帖", (result as PostSubmitResult.SiteError).message)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun submitPost_concurrentDoubleClickIsSerializedSoSecondCallerHitsCooldownWithSinglePost() = runBlocking {
        val stack = createStack()
        val info = minimalPrePostInfo(formhash = "mutexfh")
        val firstPostArrived = CountDownLatch(1)
        val allowFirstPostFinish = CountDownLatch(1)
        val postCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    postCount.incrementAndGet()
                    firstPostArrived.countDown()
                    assertTrue(allowFirstPostFinish.await(5, TimeUnit.SECONDS))
                    return MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", "/forum/viewthread.php?tid=888")
                        .build()
                }
                return gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser"))
            }
        }

        withTimeout(10.seconds) {
            val first = async(Dispatchers.Default) {
                stack.postRepo.submitPost(
                    PostSubmitRequest.ReplyThread(tid = "888", content = "双击内容", prePostInfo = info),
                )
            }
            assertTrue(firstPostArrived.await(5, TimeUnit.SECONDS))

            val second = async(Dispatchers.Default) {
                stack.postRepo.submitPost(
                    PostSubmitRequest.ReplyThread(tid = "888", content = "双击内容", prePostInfo = info),
                )
            }

            allowFirstPostFinish.countDown()
            val firstResult = first.await()
            val secondResult = second.await()

            assertTrue("First call should succeed: $firstResult", firstResult is PostSubmitResult.Success)
            assertEquals(PostSubmitResult.CooldownActive(30), secondResult)
            assertEquals(1, postCount.get())
        }
    }

    @Test
    fun postModule_hasExpectedHiltSingletonBindings() {
        val moduleClass = PostModule::class.java
        assertNotNull(moduleClass.getAnnotation(Module::class.java))

        // @InstallIn uses CLASS retention, so verify the declaration in PostModule.kt directly.
        val moduleSourceCandidates = listOf(
            java.io.File("src/main/java/net/jejer/hipda/di/PostModule.kt"),
            java.io.File("hipda/src/main/java/net/jejer/hipda/di/PostModule.kt"),
        )
        val moduleSource = moduleSourceCandidates.firstOrNull { it.isFile }?.readText()
        assertNotNull("PostModule.kt should exist", moduleSource)
        assertTrue(moduleSource!!.contains("@InstallIn(SingletonComponent::class)"))

        val bindPost = moduleClass.getDeclaredMethod("bindPostRepository", DefaultPostRepository::class.java)
        assertNotNull(bindPost.getAnnotation(Binds::class.java))
        assertNotNull(bindPost.getAnnotation(Singleton::class.java))
        assertEquals(PostRepository::class.java, bindPost.returnType)

        val bindUpload = moduleClass.getDeclaredMethod(
            "bindImageUploadRepository",
            DefaultImageUploadRepository::class.java,
        )
        assertNotNull(bindUpload.getAnnotation(Binds::class.java))
        assertNotNull(bindUpload.getAnnotation(Singleton::class.java))
        assertEquals(ImageUploadRepository::class.java, bindUpload.returnType)
    }

    @Test
    fun submitPost_deleteReplyRedirectsToViewthreadWithThreadDeletedFalse() = runBlocking {
        val stack = createStack()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=777&page=2")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser")))

        val res = stack.postRepo.submitPost(
            PostSubmitRequest.DeletePost(
                fid = 2,
                tid = "777",
                pid = "999",
                page = 2,
                prePostInfo = minimalPrePostInfo("delfh2"),
            ),
        )
        assertTrue(res is PostSubmitResult.Success)
        val success = res as PostSubmitResult.Success
        assertEquals("777", success.tid)
        assertFalse(success.threadDeleted)
        assertEquals(30, stack.postRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun submitPost_realNameBindChallengeAndNetworkErrorPreserveDraftAndSkipCooldown() = runBlocking {
        val stack = createStack(
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
        val key = DraftKey.reply("2001")
        stack.postRepo.saveDraft(key = key, content = "重要草稿内容")
        val info = minimalPrePostInfo("errfh")

        // 1) Real-name bind redirect on submit
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/memcp.php?action=bind")
                .build(),
        )
        server.enqueue(gbkHtmlResponse("<html><body>实名绑定</body></html>"))
        val bindRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "2001", content = "重要草稿内容", draftKey = key, prePostInfo = info),
        )
        assertEquals(PostSubmitResult.RealNameBindRequired, bindRes)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())

        // 2) Cloudflare 403 challenge incomplete
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("<html><body>Just a moment...</body></html>")
                .build(),
        )
        val challengeRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "2001", content = "重要草稿内容", draftKey = key, prePostInfo = info),
        )
        assertTrue("Expected ChallengeRequired but got $challengeRes", challengeRes is PostSubmitResult.ChallengeRequired)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())

        // 3) HTTP 500 network/server error
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())
        val httpErrRes = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(tid = "2001", content = "重要草稿内容", draftKey = key, prePostInfo = info),
        )
        assertTrue("Expected NetworkError but got $httpErrRes", httpErrRes is PostSubmitResult.NetworkError)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())

        // Draft must remain intact after all 3 failure modes
        draftDao.observe(key).test {
            assertEquals("重要草稿内容", awaitItem()?.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun submitPost_invalidInputsReturnInvalidInputWithZeroRequestsAndNoCooldown() = runBlocking {
        val stack = createStack()
        val info = minimalPrePostInfo("invfh")

        assertTrue(
            stack.postRepo.submitPost(
                PostSubmitRequest.NewThread(fid = 0, subject = "标题", content = "正文", prePostInfo = info),
            ) is PostSubmitResult.InvalidInput,
        )
        assertTrue(
            stack.postRepo.submitPost(
                PostSubmitRequest.NewThread(fid = 2, subject = "   ", content = "正文", prePostInfo = info),
            ) is PostSubmitResult.InvalidInput,
        )
        assertTrue(
            stack.postRepo.submitPost(
                PostSubmitRequest.ReplyThread(tid = "abc", content = "正文", prePostInfo = info),
            ) is PostSubmitResult.InvalidInput,
        )
        assertTrue(
            stack.postRepo.submitPost(
                PostSubmitRequest.QuotePost(tid = "100", pid = "", content = "正文", prePostInfo = info),
            ) is PostSubmitResult.InvalidInput,
        )
        assertTrue(
            stack.postRepo.fetchPrePost(PrePostRequest.NewThread(fid = -1)) is PrePostResult.InvalidInput,
        )
        assertEquals(0, server.requestCount)
        assertEquals(0, stack.postRepo.getRemainingCooldownSeconds())
    }

    @Test
    fun wrapBareUrls_preservesParenthesesPunctuationAndCaseInsensitiveBbcodeBlocks() {
        val input =
            "看 (https://example.com/a?x=1), 维基 https://en.wikipedia.org/wiki/Foo_(bar). " +
                "[URL]https://already.wrapped[/URL] [img]https://a.com/1.png[/img] [推荐] https://after.tag"
        val expected =
            "看 ([url]https://example.com/a?x=1[/url]), 维基 [url]https://en.wikipedia.org/wiki/Foo_(bar)[/url]. " +
                "[URL]https://already.wrapped[/URL] [img]https://a.com/1.png[/img] [推荐] [url]https://after.tag[/url]"
        assertEquals(expected, DefaultPostRepository.wrapBareUrlsWithBbcode(input))
    }

    @Test
    fun fetchPrePost_andSubmitPost_expiredSessionTriggersSilentReloginAndRetriesSuccessfully() = runBlocking {
        val stack = createStack()
        withTimeout(3.seconds) {
            stack.authRepo.currentAccount.first { it?.uid == "10000" }
        }

        val prePostAttempts = AtomicInteger(0)
        val submitPostAttempts = AtomicInteger(0)
        val loginSubmitAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    request.method == "GET" && target.contains("post.php?action=newthread&fid=2") -> {
                        if (prePostAttempts.incrementAndGet() == 1) {
                            gbkHtmlResponse(PostTestFixtures.loggedOutHtml("请先登录"))
                        } else {
                            gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "refetched_fh"))
                        }
                    }

                    request.method == "POST" && target.contains("post.php?action=reply&tid=555&replysubmit=yes") -> {
                        if (submitPostAttempts.incrementAndGet() == 1) {
                            gbkHtmlResponse(PostTestFixtures.loggedOutHtml("您还未登录"))
                        } else {
                            val pairs = decodeGbkFormPairs(request.body?.utf8().orEmpty()).toMap()
                            assertEquals("重登后重试正文", pairs["message"])
                            MockResponse.Builder()
                                .code(302)
                                .addHeader("Location", "/forum/viewthread.php?tid=555&page=1")
                                .build()
                        }
                    }

                    request.method == "GET" && target.contains("viewthread.php?tid=555") -> {
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml("10000", "HiUser", "<div id=\"postlist\"></div>"))
                    }

                    request.method == "POST" && target.contains("logging.php?action=login&loginsubmit=yes") -> {
                        loginSubmitAttempts.incrementAndGet()
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=gbk")
                            .body(PostTestFixtures.gbkBuffer("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser]]></root>"""))
                            .build()
                    }

                    request.method == "GET" && target.contains("logging.php?action=login") -> {
                        gbkHtmlResponse(PostTestFixtures.loginPageHtml("relogin_fh"))
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        // 1) fetchPrePost: expired session triggers silent relogin and retries GET (locks requireAuth = true)
        val prePostResult = stack.postRepo.fetchPrePost(PrePostRequest.NewThread(fid = 2))
        assertTrue("Expected fetchPrePost Success after silent relogin, got $prePostResult", prePostResult is PrePostResult.Success)
        val info = (prePostResult as PrePostResult.Success).info
        assertEquals("refetched_fh", info.formhash)
        assertEquals(2, prePostAttempts.get())
        assertEquals(1, loginSubmitAttempts.get())

        // 2) submitPost (with pre-supplied prePostInfo): expired session triggers silent relogin and retries POST (locks requireAuth = true)
        val submitResult = stack.postRepo.submitPost(
            PostSubmitRequest.ReplyThread(
                tid = "555",
                content = "重登后重试正文",
                prePostInfo = info,
            ),
        )
        assertTrue("Expected submitPost Success after silent relogin, got $submitResult", submitResult is PostSubmitResult.Success)
        assertEquals("555", (submitResult as PostSubmitResult.Success).tid)
        assertEquals(2, submitPostAttempts.get())
        assertEquals(2, loginSubmitAttempts.get())
    }

    @Test
    fun cooldownUntilMs_flowAutomaticallyEmitsZeroOnAccountSwitchAndLogoutWithoutPollingGetter() = runBlocking {
        val accountA = SavedAccount(
            uid = "10000",
            username = "HiUser",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw"),
            lastLoginAt = 1_000L,
        )
        val accountB = SavedAccount(
            uid = "20000",
            username = "HiUserB",
            passwordMd5 = computeLegacyPasswordMd5("secret_pw_b"),
            lastLoginAt = 900L,
        )
        credentialsStore.upsert(accountB)
        val stack = createStack(seedLoggedInAccount = accountA)
        withTimeout(3.seconds) {
            stack.authRepo.currentAccount.first { it?.uid == accountA.uid }
        }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    request.method == "POST" && target.contains("post.php?action=reply") -> {
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", "/forum/viewthread.php?tid=666")
                            .build()
                    }

                    request.method == "GET" && target.contains("viewthread.php?tid=666") -> {
                        val active = stack.authRepo.currentAccount.value ?: accountA
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml(active.uid, active.username))
                    }

                    request.method == "GET" && target.contains("logging.php?action=login") -> {
                        gbkHtmlResponse(PostTestFixtures.loginPageHtml("switch_fh"))
                    }

                    request.method == "POST" && target.contains("logging.php?action=login&loginsubmit=yes") -> {
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=gbk")
                            .body(PostTestFixtures.gbkBuffer("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUserB]]></root>"""))
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val info = minimalPrePostInfo(formhash = "flowfh")
        stack.postRepo.cooldownUntilMs.test(timeout = 5.seconds) {
            assertEquals(0L, awaitItem())

            // Submit under account A -> cooldown starts
            val firstRes = stack.postRepo.submitPost(
                PostSubmitRequest.ReplyThread(tid = "666", content = "账号A回复", prePostInfo = info),
            )
            assertTrue("Expected Success under account A, got $firstRes", firstRes is PostSubmitResult.Success)
            val cooldownA = awaitItem()
            assertEquals(virtualClockMs.get() + DefaultPostRepository.POST_COOLDOWN_MS, cooldownA)

            // Switch to account B WITHOUT calling getRemainingCooldownSeconds() -> background collector MUST push 0L
            val switchRes = stack.authRepo.switchAccount(accountB.uid)
            assertTrue("Expected switchAccount Success, got $switchRes", switchRes is LoginResult.Success)
            assertEquals(
                "Account switch must automatically push 0L to cooldownUntilMs via background collector",
                0L,
                awaitItem(),
            )

            // Submit under account B -> fresh cooldown starts
            virtualClockMs.addAndGet(1_000L)
            val secondRes = stack.postRepo.submitPost(
                PostSubmitRequest.ReplyThread(tid = "666", content = "账号B回复", prePostInfo = info),
            )
            assertTrue("Expected Success under account B, got $secondRes", secondRes is PostSubmitResult.Success)
            val cooldownB = awaitItem()
            assertEquals(virtualClockMs.get() + DefaultPostRepository.POST_COOLDOWN_MS, cooldownB)

            // Logout WITHOUT calling getRemainingCooldownSeconds() -> background collector MUST push 0L
            stack.authRepo.logout()
            assertEquals(
                "Logout must automatically push 0L to cooldownUntilMs via background collector",
                0L,
                awaitItem(),
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun minimalPrePostInfo(formhash: String): PrePostInfo = PrePostInfo(
        formhash = formhash,
        uid = "10000",
        hash = "abcdef0123456789",
        subject = null,
        text = "",
        quoteText = "",
        isDeletable = true,
        maxUploadFileSizeBytes = 2 * 1024 * 1024,
        noticeAuthor = null,
        noticeAuthorMsg = null,
        noticeTrimStr = null,
        existingImages = emptyList(),
        existingAttachments = emptyList(),
        typeId = "0",
        typeOptions = emptyMap(),
        meta = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "10000", username = "HiUser"),
            formhash = formhash,
            errorMessage = null,
            promptCounts = PromptCounts(),
        ),
    )

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal object PostTestFixtures {
    private val GBK: Charset = Charset.forName("GBK")

    fun gbkBuffer(text: String): Buffer = Buffer().write(text.toByteArray(GBK))

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

    fun loginPageHtml(formhash: String): String = """
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

    fun prePostHtml(
        formhash: String,
        uid: String = "10000",
        username: String = "HiUser",
        hash: String = "0123456789abcdef0123456789abcdef",
        subject: String? = null,
        textarea: String = "",
        isDeletable: Boolean = false,
        maxSizeLabel: String = "8MB",
        noticeAuthor: String? = null,
        noticeAuthorMsg: String? = null,
        noticeTrimStr: String? = null,
        existingImages: List<String> = emptyList(),
        typeOptions: List<Pair<String, String>> = emptyList(),
        selectedTypeId: String? = null,
    ): String {
        val subjectTag = subject?.let { """<input type="text" name="subject" value="$it" />""" }.orEmpty()
        val deleteTag = if (isDeletable) """<input type="checkbox" id="delete" name="delete" value="1" />""" else ""
        val noticeTags = buildString {
            noticeAuthor?.let { append("""<input type="hidden" name="noticeauthor" value="$it" />""") }
            noticeAuthorMsg?.let { append("""<input type="hidden" name="noticeauthormsg" value="$it" />""") }
            noticeTrimStr?.let { append("""<input type="hidden" name="noticetrimstr" value="$it" />""") }
        }
        val imageTags = if (existingImages.isEmpty()) {
            ""
        } else {
            val imgs = existingImages.joinToString("") { id -> """<img id="image_$id" src="attachments/$id.jpg" />""" }
            """<div class="upfilelist">$imgs</div>"""
        }
        val typeSelect = if (typeOptions.isEmpty()) {
            ""
        } else {
            val opts = typeOptions.joinToString("") { (value, label) ->
                val selected = if (value == selectedTypeId) """ selected="selected"""" else ""
                """<option value="$value"$selected>$label</option>"""
            }
            """<select id="typeid" name="typeid">$opts</select>"""
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta http-equiv="Content-Type" content="text/html; charset=gbk" />
              <script type="text/javascript">var discuz_uid = $uid, fid = 2;</script>
            </head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite></div>
              <form id="postform" method="post">
                <input type="hidden" name="formhash" id="formhash" value="$formhash" />
                <input type="hidden" name="hash" value="$hash" />
                $subjectTag
                $typeSelect
                $deleteTag
                $noticeTags
                <textarea name="message">$textarea</textarea>
                <div class="uploadinfo">文件尺寸: <strong>小于 $maxSizeLabel </strong></div>
                $imageTags
              </form>
            </body>
            </html>
        """.trimIndent()
    }
}

internal fun gbkHtmlResponse(
    html: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(PostTestFixtures.gbkBuffer(html))
    .build()

internal fun decodeGbkFormPairs(rawBody: String): List<Pair<String, String>> {
    if (rawBody.isBlank()) return emptyList()
    return rawBody.split('&').map { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) {
            URLDecoder.decode(pair, "GBK") to ""
        } else {
            URLDecoder.decode(pair.substring(0, idx), "GBK") to
                URLDecoder.decode(pair.substring(idx + 1), "GBK")
        }
    }
}

internal class PostTestCredentialsStore : CredentialsStore {
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

internal class PostTestSettingsRepository(
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
