package net.jejer.hipda.data.threadlist

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dagger.Binds
import dagger.Module
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.HiDatabase
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
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.di.ThreadListModule
import net.jejer.hipda.parser.LegacyTestSupport
import net.jejer.hipda.parser.threadlist.ThreadSummary
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThreadListRepositoryTest {

    private lateinit var database: HiDatabase
    private lateinit var browseHistoryDao: BrowseHistoryDao
    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: FakeThreadListSettingsRepository
    private lateinit var authRepository: FakeThreadListAuthRepository
    private lateinit var blacklistRepository: FakeBlacklistRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    private val defaultAccount = SavedAccount(
        uid = "10000",
        username = "HiUser",
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 1_000L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        browseHistoryDao = database.browseHistoryDao()
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        settingsRepository = FakeThreadListSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = FakeThreadListAuthRepository(initialAccount = defaultAccount)
        blacklistRepository = FakeBlacklistRepository()
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
        database.close()
    }

    private fun createRepository(
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): DefaultThreadListRepository {
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        ).also(clientsToClose::add)
        val gateway = SiteAccessGateway(
            client = client,
            detector = ChallengeDetector(),
            solver = solver,
            clock = { nowMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val forumHttpClient = ForumHttpClient(
            siteAccessGateway = gateway,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
        )
        return DefaultThreadListRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            settingsRepository = settingsRepository,
            blacklistRepository = blacklistRepository,
            browseHistoryDao = browseHistoryDao,
        )
    }

    @Test
    fun loadPage_realSamplesLastpostP1P2AndDateline_buildsUrlsAndParsesFields() = runBlocking {
        val baseServer = server.url("").toString().trimEnd('/')
        settingsRepository.setForumServer("$baseServer/forum")

        val p1Lastpost = LegacyTestSupport.loadSample("thread_list/forum_2_p1_lastpost.html")
        val p2Lastpost = LegacyTestSupport.loadSample("thread_list/forum_2_p2_lastpost.html")
        val p1Dateline = LegacyTestSupport.loadSample("thread_list/forum_2_p1_dateline.html")

        server.enqueue(gbkHtmlResponse(p1Lastpost))
        server.enqueue(gbkHtmlResponse(p2Lastpost))
        server.enqueue(gbkHtmlResponse(p1Dateline))
        server.enqueue(gbkHtmlResponse(p1Lastpost))
        server.enqueue(gbkHtmlResponse(p1Dateline))

        val repo = createRepository()

        // 1. Real sample p1 lastpost (default sortByPostTime from AppSettings == false)
        val res1 = repo.loadPage(ThreadListQuery(fid = 2), startPage = 1)
        assertTrue("Expected ThreadListBatchResult, got $res1", res1 is ThreadListBatchResult)
        val batch1 = res1 as ThreadListBatchResult
        assertEquals(75, batch1.items.size)
        assertEquals(1, batch1.fetchedPage)
        assertEquals(1000, batch1.lastPage)
        assertTrue(batch1.hasMore)
        assertEquals(1, batch1.pagesFetchedInBatch)
        assertFalse(batch1.resolvedSortByPostTime)
        assertEquals(ThreadListBackfillStopReason.TARGET_REACHED, batch1.stopReason)
        assertNull(batch1.partialError)
        assertEquals("10000", batch1.meta.session.uid)
        val expectedAvatarPrefix = "$baseServer/forum/uc_server/data/avatar/"
        assertTrue(
            "Expected avatarUrl starting with $expectedAvatarPrefix without duplicate /forum, got ${batch1.items.first().summary.avatarUrl}",
            batch1.items.first().summary.avatarUrl?.startsWith(expectedAvatarPrefix) == true,
        )
        assertFalse(batch1.items.first().summary.avatarUrl.orEmpty().contains("/forum/forum/"))
        val appleItem = batch1.items.firstOrNull { it.tid == "3477944" }
        assertNotNull(appleItem)
        assertTrue(appleItem!!.summary.title.contains("🍎"))
        assertFalse(appleItem.isVisited)

        // 2. Real sample p2 lastpost
        val res2 = repo.loadPage(ThreadListQuery(fid = 2), startPage = 2)
        val batch2 = res2 as ThreadListBatchResult
        assertEquals(75, batch2.items.size)
        assertEquals(2, batch2.fetchedPage)
        assertEquals(1000, batch2.lastPage)
        assertTrue(batch2.hasMore)
        val poopItem = batch2.items.firstOrNull { it.tid == "3477896" }
        assertNotNull(poopItem)
        assertTrue(poopItem!!.summary.title.contains("💩"))

        // 3. Real sample p1 dateline resolved from AppSettings.isSortByPostTime(2) == true
        settingsRepository.setSortByPostTime(fid = 2, sortByPostTime = true)
        val res3 = repo.loadPage(ThreadListQuery(fid = 2, sortByPostTime = null), startPage = 1)
        val batch3 = res3 as ThreadListBatchResult
        assertEquals(75, batch3.items.size)
        assertTrue(batch3.resolvedSortByPostTime)

        // 4. Explicit sortByPostTime = false overrides AppSettings
        val res4 = repo.loadPage(ThreadListQuery(fid = 2, sortByPostTime = false), startPage = 1)
        assertFalse((res4 as ThreadListBatchResult).resolvedSortByPostTime)

        // 5. Optional typeId (BS forum) + sortByPostTime = true
        val res5 = repo.loadPage(ThreadListQuery(fid = 6, sortByPostTime = true, typeId = "7"), startPage = 1)
        assertTrue(res5 is ThreadListBatchResult)

        assertEquals("/forum/forumdisplay.php?fid=2&page=1", server.takeRequest().target)
        assertEquals("/forum/forumdisplay.php?fid=2&page=2", server.takeRequest().target)
        assertEquals("/forum/forumdisplay.php?fid=2&page=1&orderby=dateline", server.takeRequest().target)
        assertEquals("/forum/forumdisplay.php?fid=2&page=1", server.takeRequest().target)
        assertEquals("/forum/forumdisplay.php?fid=6&page=1&filter=type&typeid=7&orderby=dateline", server.takeRequest().target)
    }

    @Test
    fun loadPageAndObserveFiltered_dropInvalidAndBlankTidsAndDeduplicateAcrossPages() = runBlocking {
        // Page 1 has invalid tids ("", "0", "-5", "abc", "+99") + duplicate "101" + valid "102"
        val page1Rows = listOf(
            syntheticRowHtml(rawHref = "viewthread.php?extra=page%3D1", title = "MissingTid"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=0", title = "ZeroTid"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=-5", title = "NegativeTid"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=abc", title = "AlphaTid"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=+99", title = "SignedTid"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=101", title = "First101"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=101", title = "Duplicate101SamePage"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=102", title = "Valid102"),
        )
        // Page 2 duplicates "102" and supplies 9 more valid rows ("103".."111") -> reaches 11 visible items (>= 10)
        val page2Rows = listOf(
            syntheticRowHtml(rawHref = "viewthread.php?tid=102", title = "Duplicate102CrossPage"),
        ) + (103..111).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Thread$tid")
        }

        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = page1Rows, currentPage = 1, lastPage = 5)))
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = page2Rows, currentPage = 2, lastPage = 5)))

        val repo = createRepository()
        val result = repo.loadPage(
            query = ThreadListQuery(fid = 2),
            startPage = 1,
            minVisibleItems = 10,
            maxAutoBackfillPages = 20,
        )

        assertTrue("Expected ThreadListBatchResult, got $result", result is ThreadListBatchResult)
        val batch = result as ThreadListBatchResult
        val expectedTids = (101..111).map(Int::toString)
        assertEquals(expectedTids, batch.items.map { it.tid })
        assertEquals("First101", batch.items.first { it.tid == "101" }.summary.title)
        assertEquals("Valid102", batch.items.first { it.tid == "102" }.summary.title)
        assertEquals(2, batch.fetchedPage)
        assertEquals(2, batch.pagesFetchedInBatch)

        // Direct observeFiltered must also reject invalid/blank tids and deduplicate preserving first order
        val rawStream = MutableStateFlow(
            listOf(
                sampleSummary(tid = "", title = "Empty"),
                sampleSummary(tid = "0", title = "Zero"),
                sampleSummary(tid = "not-a-number", title = "Bad"),
                sampleSummary(tid = "500", title = "First500"),
                sampleSummary(tid = "500", title = "Second500"),
                sampleSummary(tid = "501", title = "Valid501"),
            ),
        )
        val filtered = repo.observeFiltered(rawStream).first()
        assertEquals(listOf("500", "501"), filtered.map { it.tid })
        assertEquals("First500", filtered.first().summary.title)
    }

    @Test
    fun autoBackfill_exceedsLegacyFivePageLimitAcrossSevenPagesAndSucceedsWithoutError() = runBlocking {
        settingsRepository.setShowStickyThreads(false)
        blacklistRepository.setBlocked(setOf("SpamBot"))

        // Pages 1..6: all threads are either sticky or authored by SpamBot (0 visible items across 6 pages > legacy 5-page cap!)
        for (page in 1..6) {
            val rows = listOf(
                syntheticRowHtml(
                    rawHref = "viewthread.php?tid=${page * 100 + 1}",
                    title = "StickyP$page",
                    isSticky = true,
                    author = "Admin",
                ),
                syntheticRowHtml(
                    rawHref = "viewthread.php?tid=${page * 100 + 2}",
                    title = "BlockedP$page",
                    isSticky = false,
                    author = "SpamBot",
                ),
            )
            server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = rows, currentPage = page, lastPage = 10)))
        }
        // Page 7: 12 normal visible threads
        val page7Rows = (701..712).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Visible$tid", author = "NormalUser")
        }
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = page7Rows, currentPage = 7, lastPage = 10)))

        val repo = createRepository()
        val result = repo.loadPage(
            query = ThreadListQuery(fid = 2),
            startPage = 1,
            minVisibleItems = 10,
            maxAutoBackfillPages = 20,
        )

        assertTrue("Expected backfill across 7 pages to succeed without error, got $result", result is ThreadListBatchResult)
        val batch = result as ThreadListBatchResult
        assertEquals(12, batch.items.size)
        assertEquals((701..712).map(Int::toString), batch.items.map { it.tid })
        assertEquals(7, batch.fetchedPage)
        assertEquals(10, batch.lastPage)
        assertTrue(batch.hasMore)
        assertEquals(7, batch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.TARGET_REACHED, batch.stopReason)
        assertNull(batch.partialError)
        // Raw threads preserve sticky/blocked rows so later unblocking or enabling sticky works with 0 requests
        assertEquals(24, batch.rawThreads.size)

        for (page in 1..7) {
            assertEquals("/forum/forumdisplay.php?fid=2&page=$page", server.takeRequest().target)
        }
    }

    @Test
    fun autoBackfill_hardStopsAtLastPageEmptyPageAndSafetyLimitWithoutInfiniteLoop() = runBlocking {
        val repo = createRepository()

        // 1. Hard stop: REACHED_LAST_PAGE (3 pages total, only 2 visible items per page -> 6 < 10)
        for (page in 1..3) {
            val rows = listOf(
                syntheticRowHtml(rawHref = "viewthread.php?tid=${1000 + page * 10 + 1}", title = "LastPageA$page"),
                syntheticRowHtml(rawHref = "viewthread.php?tid=${1000 + page * 10 + 2}", title = "LastPageB$page"),
            )
            server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = rows, currentPage = page, lastPage = 3)))
        }
        val lastPageRes = repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 10, maxAutoBackfillPages = 20)
        val lastPageBatch = lastPageRes as ThreadListBatchResult
        assertEquals(6, lastPageBatch.items.size)
        assertEquals(3, lastPageBatch.fetchedPage)
        assertEquals(3, lastPageBatch.lastPage)
        assertFalse(lastPageBatch.hasMore)
        assertEquals(3, lastPageBatch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.REACHED_LAST_PAGE, lastPageBatch.stopReason)

        // 2a. Hard stop: EMPTY_PAGE (!hasThreadRows) on page 2 after page 1 claimed lastPage=50
        server.enqueue(
            gbkHtmlResponse(
                syntheticForumHtml(
                    rows = listOf(
                        syntheticRowHtml(rawHref = "viewthread.php?tid=2001", title = "P1A"),
                        syntheticRowHtml(rawHref = "viewthread.php?tid=2002", title = "P1B"),
                    ),
                    currentPage = 1,
                    lastPage = 50,
                ),
            ),
        )
        server.enqueue(gbkHtmlResponse(loggedInHtml(body = """<div class="threadlist"><p class="nodata">本版块尚无主题</p></div>""")))
        val emptyPageRes = repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 10, maxAutoBackfillPages = 20)
        val emptyPageBatch = emptyPageRes as ThreadListBatchResult
        assertEquals(listOf("2001", "2002"), emptyPageBatch.items.map { it.tid })
        assertEquals(2, emptyPageBatch.fetchedPage)
        assertFalse(emptyPageBatch.hasMore)
        assertEquals(2, emptyPageBatch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.EMPTY_PAGE, emptyPageBatch.stopReason)

        // 2b. Hard stop: EMPTY_PAGE (hasThreadRows=true with separatorline only -> threads.isEmpty())
        server.enqueue(
            gbkHtmlResponse(
                loggedInHtml(
                    body = """
                        <div class="pages"><a href="forumdisplay.php?fid=2&amp;page=9">9</a><strong>1</strong></div>
                        <table><tbody id="separatorline"><tr><td>分隔行</td></tr></tbody></table>
                    """.trimIndent(),
                ),
            ),
        )
        val separatorOnlyRes = repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 10, maxAutoBackfillPages = 20)
        val separatorOnlyBatch = separatorOnlyRes as ThreadListBatchResult
        assertTrue(separatorOnlyBatch.items.isEmpty())
        assertFalse(separatorOnlyBatch.hasMore)
        assertEquals(1, separatorOnlyBatch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.EMPTY_PAGE, separatorOnlyBatch.stopReason)

        // 3. Hard stop: SAFETY_LIMIT_REACHED (all pages filtered out, server reports lastPage=100)
        blacklistRepository.setBlocked(setOf("FloodUser"))
        val safetyCap = 8
        for (page in 1..12) {
            val rows = listOf(
                syntheticRowHtml(rawHref = "viewthread.php?tid=${3000 + page}", title = "Flood$page", author = "FloodUser"),
            )
            server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = rows, currentPage = page, lastPage = 100)))
        }
        val reqCountBeforeSafety = server.requestCount
        val safetyRes = repo.loadPage(
            query = ThreadListQuery(fid = 2),
            startPage = 1,
            minVisibleItems = 10,
            maxAutoBackfillPages = safetyCap,
        )
        assertTrue("Safety limit must return collected result without error, got $safetyRes", safetyRes is ThreadListBatchResult)
        val safetyBatch = safetyRes as ThreadListBatchResult
        assertTrue(safetyBatch.items.isEmpty())
        assertEquals(safetyCap, safetyBatch.fetchedPage)
        assertEquals(100, safetyBatch.lastPage)
        assertTrue(safetyBatch.hasMore)
        assertEquals(safetyCap, safetyBatch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.SAFETY_LIMIT_REACHED, safetyBatch.stopReason)
        assertEquals(reqCountBeforeSafety + safetyCap, server.requestCount)
    }

    @Test
    fun autoBackfill_midBatchFailureHandlesPartialResultsAndRethrowsCancellation() = runBlocking {
        val repo = createRepository()

        // Case 1: Page 1 has 3 visible items, page 2 returns HTTP 500 -> preserves page 1 items + partialError
        server.enqueue(
            gbkHtmlResponse(
                syntheticForumHtml(
                    rows = (401..403).map { tid ->
                        syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Partial$tid")
                    },
                    currentPage = 1,
                    lastPage = 10,
                ),
            ),
        )
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())

        val partialRes = repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 10)
        assertTrue("Expected partial ThreadListBatchResult, got $partialRes", partialRes is ThreadListBatchResult)
        val partialBatch = partialRes as ThreadListBatchResult
        assertEquals(listOf("401", "402", "403"), partialBatch.items.map { it.tid })
        assertEquals(1, partialBatch.fetchedPage)
        assertTrue(partialBatch.hasMore)
        assertEquals(1, partialBatch.pagesFetchedInBatch)
        assertEquals(ThreadListBackfillStopReason.PARTIAL_ERROR, partialBatch.stopReason)
        assertTrue(partialBatch.partialError is ThreadListError.NetworkError)
        assertEquals(500, (partialBatch.partialError as ThreadListError.NetworkError).statusCode)

        // Case 2: Page 1 has 0 visible items (all sticky), page 2 fails -> returns explicit NetworkError
        settingsRepository.setShowStickyThreads(false)
        server.enqueue(
            gbkHtmlResponse(
                syntheticForumHtml(
                    rows = listOf(
                        syntheticRowHtml(rawHref = "viewthread.php?tid=450", title = "StickyOnly", isSticky = true),
                    ),
                    currentPage = 1,
                    lastPage = 10,
                ),
            ),
        )
        server.enqueue(MockResponse.Builder().code(503).body("Unavailable").build())

        val zeroVisibleFailure = repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 10)
        assertTrue("Expected NetworkError when 0 visible items collected before failure, got $zeroVisibleFailure", zeroVisibleFailure is ThreadListError.NetworkError)
        assertEquals(503, (zeroVisibleFailure as ThreadListError.NetworkError).statusCode)

        // Case 3: CancellationException must never be swallowed
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return gbkHtmlResponse(syntheticForumHtml(rows = emptyList(), currentPage = 1, lastPage = 1))
            }
        }
        val job = async(Dispatchers.Default) {
            repo.loadPage(ThreadListQuery(fid = 2))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            job.cancel()
            try {
                job.await()
                fail("Expected CancellationException to propagate")
            } catch (_: CancellationException) {
                // Expected
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun reactiveFlows_stickyBlacklistAndRoomBrowseHistoryUpdateImmediatelyWithZeroExtraRequests() = runBlocking {
        val rows = listOf(
            syntheticRowHtml(rawHref = "viewthread.php?tid=201", title = "StickyTop", isSticky = true, author = "Admin"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=202", title = "ByTroll", author = "TrollUser"),
            syntheticRowHtml(rawHref = "viewthread.php?tid=203", title = "ByFriend", author = "FriendUser"),
        ) + (204..212).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Normal$tid", author = "Member$tid")
        }
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = rows, currentPage = 1, lastPage = 5)))

        val repo = createRepository()
        val query = ThreadListQuery(fid = 2)

        val refreshRes = repo.refresh(query)
        assertTrue("Expected ThreadListBatchResult, got $refreshRes", refreshRes is ThreadListBatchResult)
        val requestCountAfterLoad = server.requestCount
        assertEquals(1, requestCountAfterLoad)

        repo.observeThreadList(query).test {
            val initial = awaitItem()
            assertEquals(12, initial.items.size)
            assertTrue(initial.items.any { it.tid == "201" })
            assertTrue(initial.items.any { it.tid == "202" })
            assertFalse(initial.items.first { it.tid == "203" }.isVisited)

            // 1. Toggle showStickyThreads -> false (0 network requests)
            settingsRepository.setShowStickyThreads(false)
            val hideSticky = awaitItem()
            assertEquals(11, hideSticky.items.size)
            assertFalse(hideSticky.items.any { it.tid == "201" })

            // 2. Toggle showStickyThreads -> true (reappears at original index 0, 0 network requests)
            settingsRepository.setShowStickyThreads(true)
            val showSticky = awaitItem()
            assertEquals(12, showSticky.items.size)
            assertEquals("201", showSticky.items.first().tid)

            // 3. Add TrollUser to blacklist (0 network requests)
            blacklistRepository.setBlocked(setOf("TrollUser"))
            val blockedState = awaitItem()
            assertEquals(11, blockedState.items.size)
            assertFalse(blockedState.items.any { it.tid == "202" })

            // 4. Remove / undo TrollUser from blacklist (reappears in place, 0 network requests)
            blacklistRepository.setBlocked(emptySet())
            val unblockedState = awaitItem()
            assertEquals(12, unblockedState.items.size)
            assertEquals("202", unblockedState.items[1].tid)

            // 5. Write browse_history in Room (D5 opens thread 203 -> isVisited flips true, 0 network requests)
            browseHistoryDao.upsert(
                BrowseHistoryEntity(
                    tid = "203",
                    title = "ByFriend",
                    fid = "2",
                    forumName = "Discovery",
                    author = "FriendUser",
                    lastVisitedAt = 1_700_000_000_000L,
                ),
            )
            val visitedState = awaitItem()
            assertTrue(visitedState.items.first { it.tid == "203" }.isVisited)
            assertFalse(visitedState.items.first { it.tid == "204" }.isVisited)

            // 6. Delete browse_history in Room -> isVisited flips back to false (0 network requests)
            browseHistoryDao.delete("203")
            val unvisitedState = awaitItem()
            assertFalse(unvisitedState.items.first { it.tid == "203" }.isVisited)

            cancelAndIgnoreRemainingEvents()
        }

        // Verify BrowseHistoryDao.observeVisitedTids() directly as well
        browseHistoryDao.observeVisitedTids().test {
            assertEquals(emptyList<String>(), awaitItem())
            browseHistoryDao.upsert(
                BrowseHistoryEntity(
                    tid = "999",
                    title = "HistoryCheck",
                    fid = "2",
                    forumName = "Discovery",
                    author = "Someone",
                    lastVisitedAt = 100L,
                ),
            )
            assertEquals(listOf("999"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("Reactive updates must trigger ZERO extra HTTP requests", requestCountAfterLoad, server.requestCount)
    }

    @Test
    fun refreshAndLoadMore_accumulatesUniqueItemsAndStopsWithZeroRequestsAfterLastPage() = runBlocking {
        val page1Rows = (501..510).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "P1_$tid")
        }
        val page2Rows = listOf(
            syntheticRowHtml(rawHref = "viewthread.php?tid=510", title = "Overlap510"),
        ) + (511..520).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "P2_$tid")
        }

        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = page1Rows, currentPage = 1, lastPage = 2)))
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = page2Rows, currentPage = 2, lastPage = 2)))

        val repo = createRepository()
        val query = ThreadListQuery(fid = 2)

        val refreshRes = repo.refresh(query)
        assertTrue(refreshRes is ThreadListBatchResult)
        val afterRefresh = repo.observeThreadList(query).first()
        assertEquals(10, afterRefresh.items.size)
        assertTrue(afterRefresh.hasMore)
        assertEquals(1, afterRefresh.fetchedPage)

        val loadMoreRes = repo.loadMore(query)
        assertTrue(loadMoreRes is ThreadListBatchResult)
        val batch2 = loadMoreRes as ThreadListBatchResult
        assertEquals((511..520).map(Int::toString), batch2.items.map { it.tid })
        assertFalse(batch2.hasMore)

        val afterLoadMore = repo.observeThreadList(query).first()
        assertEquals((501..520).map(Int::toString), afterLoadMore.items.map { it.tid })
        assertEquals("P1_510", afterLoadMore.items.first { it.tid == "510" }.summary.title)
        assertFalse(afterLoadMore.hasMore)
        assertEquals(2, afterLoadMore.fetchedPage)

        // Subsequent loadMore when hasMore == false must make 0 network requests
        val countBeforeNoop = server.requestCount
        val noopRes = repo.loadMore(query)
        assertTrue(noopRes is ThreadListBatchResult)
        assertTrue((noopRes as ThreadListBatchResult).items.isEmpty())
        assertEquals(0, noopRes.pagesFetchedInBatch)
        assertFalse(noopRes.hasMore)
        assertEquals(countBeforeNoop, server.requestCount)
    }

    @Test
    fun authPolicy_guestCanBrowsePublicForumAndReceivesExplicitErrorsWhenRestricted() = runBlocking {
        authRepository.setActiveAccount(null)
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            SilentReloginResult.NotLoggedIn
        }

        val repo = createRepository()

        // 1. Public forum allows guest browsing (requireAuth = false -> 0 relogin attempts)
        val publicRows = (601..610).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Public$tid")
        }
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = publicRows, currentPage = 1, lastPage = 3, loggedIn = false)))
        val publicRes = repo.loadPage(ThreadListQuery(fid = 2))
        assertTrue("Guest must browse public forum, got $publicRes", publicRes is ThreadListBatchResult)
        assertEquals(10, (publicRes as ThreadListBatchResult).items.size)
        assertFalse(publicRes.meta.session.isLoggedIn)
        assertEquals(0, reloginCalls.get())

        // 2. Restricted forum returning login-required alert_error -> AuthRequired
        server.enqueue(gbkHtmlResponse(loggedOutHtml("对不起，您无权进行当前操作，请先登录")))
        val restrictedRes = repo.loadPage(ThreadListQuery(fid = 6))
        assertTrue("Expected AuthRequired for guest restricted forum, got $restrictedRes", restrictedRes is ThreadListError.AuthRequired)
        assertEquals(AuthRequiredReason.NOT_LOGGED_IN, (restrictedRes as ThreadListError.AuthRequired).reason)
        assertEquals(0, reloginCalls.get())

        // 3. Guest redirected to login form page with no threads -> AuthRequired
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><body>
                  <div id="umenu"><a href="logging.php?action=login">登录</a></div>
                  <form name="login" method="post" action="logging.php?action=login&amp;loginsubmit=yes">
                    <input type="hidden" name="formhash" value="fh_guest" />
                  </form>
                </body></html>
                """.trimIndent(),
            ),
        )
        val loginFormRes = repo.loadPage(ThreadListQuery(fid = 6))
        assertTrue("Expected AuthRequired when guest hits login form, got $loginFormRes", loginFormRes is ThreadListError.AuthRequired)
        assertEquals(0, reloginCalls.get())

        // 4. Non-auth site error for guest (e.g., nonexistent forum) -> SiteError
        server.enqueue(gbkHtmlResponse(loggedOutHtml("对不起，指定的版块不存在")))
        val missingForumRes = repo.loadPage(ThreadListQuery(fid = 9999))
        assertEquals(ThreadListError.SiteError("对不起，指定的版块不存在"), missingForumRes)
    }

    @Test
    fun authPolicy_loggedInUserGetsSingleSilentReloginAndPropagatesErrors() = runBlocking {
        val repo = createRepository()

        // 1. Expired session -> single silent relogin -> retries once and succeeds
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        val rows = (801..810).map { tid ->
            syntheticRowHtml(rawHref = "viewthread.php?tid=$tid", title = "Relogin$tid")
        }
        server.enqueue(gbkHtmlResponse(loggedOutHtml("登录已过期")))
        server.enqueue(gbkHtmlResponse(syntheticForumHtml(rows = rows, currentPage = 1, lastPage = 2, loggedIn = true)))

        val recovered = repo.loadPage(ThreadListQuery(fid = 2))
        assertTrue("Expected Success after silent relogin, got $recovered", recovered is ThreadListBatchResult)
        assertEquals(1, reloginCalls.get())
        assertEquals(10, (recovered as ThreadListBatchResult).items.size)

        // 2. Relogin suppressed -> AuthRequired(MANUALLY_LOGGED_OUT)
        authRepository.onTrySilentRelogin = null
        authRepository.nextReloginResult = SilentReloginResult.Suppressed
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        val suppressed = repo.loadPage(ThreadListQuery(fid = 2))
        assertTrue("Expected AuthRequired, got $suppressed", suppressed is ThreadListError.AuthRequired)
        assertEquals(AuthRequiredReason.MANUALLY_LOGGED_OUT, (suppressed as ThreadListError.AuthRequired).reason)

        // 3. Logged-in user with forum permission error -> SiteError (NOT AuthRequired)
        server.enqueue(
            gbkHtmlResponse(
                loggedInHtml(body = """<div class="alert_error">对不起，您没有权限访问该版块<a href="index.php">返回</a></div>"""),
            ),
        )
        val siteError = repo.loadPage(ThreadListQuery(fid = 99))
        assertEquals(ThreadListError.SiteError("对不起，您没有权限访问该版块"), siteError)

        // 4. HTTP 500 -> NetworkError(statusCode = 500)
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())
        val httpError = repo.loadPage(ThreadListQuery(fid = 2))
        assertTrue(httpError is ThreadListError.NetworkError)
        assertEquals(500, (httpError as ThreadListError.NetworkError).statusCode)

        // 5. Cloudflare 403 under BackgroundSilent -> ChallengeRequired(abandoned = true)
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build(),
        )
        val challengeError = repo.loadPage(
            query = ThreadListQuery(fid = 2),
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue(challengeError is ThreadListError.ChallengeRequired)
        assertTrue((challengeError as ThreadListError.ChallengeRequired).abandoned)
    }

    @Test
    fun invalidInputs_rejectedWithZeroNetworkRequests() = runBlocking {
        val repo = createRepository()

        assertTrue(repo.loadPage(ThreadListQuery(fid = 0)) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = -2)) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 2), startPage = 0) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 2), minVisibleItems = 0) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 2), maxAutoBackfillPages = 0) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 6, typeId = "0")) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 6, typeId = "-1")) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 6, typeId = "+7")) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 6, typeId = "7&evil=1")) is ThreadListError.InvalidInput)
        assertTrue(repo.loadPage(ThreadListQuery(fid = 6, typeId = "abc")) is ThreadListError.InvalidInput)
        assertTrue(repo.refresh(ThreadListQuery(fid = 0)) is ThreadListError.InvalidInput)
        assertTrue(repo.loadMore(ThreadListQuery(fid = -1)) is ThreadListError.InvalidInput)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun threadListModule_hasExpectedHiltBinding() {
        assertTrue(ThreadListModule::class.java.isAnnotationPresent(Module::class.java))
        val bindMethod = ThreadListModule::class.java.getDeclaredMethod(
            "bindThreadListRepository",
            DefaultThreadListRepository::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(ThreadListRepository::class.java, bindMethod.returnType)
    }

    private fun sampleSummary(
        tid: String,
        title: String,
        isSticky: Boolean = false,
        author: String = "Author",
    ): ThreadSummary = ThreadSummary(
        tid = tid,
        title = title,
        titleColor = null,
        typeName = null,
        isSticky = isSticky,
        isNew = false,
        isPoll = false,
        author = author,
        authorId = "10001",
        avatarUrl = null,
        timeCreate = "2026-11-25",
        timeUpdate = "2026-11-25 12:00",
        lastPostAuthor = author,
        replyCountText = "5",
        viewCountText = "100",
        withPic = false,
        maxPage = 1,
    )

    private fun syntheticRowHtml(
        rawHref: String,
        title: String,
        isSticky: Boolean = false,
        author: String = "AuthorA",
        authorUid: String = "20001",
    ): String {
        val folderImg = if (isSticky) "images/default/pin_1.gif" else "images/default/folder_new.gif"
        return """
            <tbody id="normalthread_${title.hashCode()}">
              <tr>
                <td class="folder"><img src="$folderImg" /></td>
                <td class="icon"></td>
                <th class="subject"><span><a href="$rawHref">$title</a></span></th>
                <td class="author"><cite><a href="space.php?uid=$authorUid">$author</a></cite><em>2026-11-25</em></td>
                <td class="nums"><strong>12</strong> / <em>345</em></td>
                <td class="lastpost"><em><a href="redirect.php?tid=1">2026-11-25 10:00</a></em><cite><a href="space.php?username=$author">$author</a></cite></td>
              </tr>
            </tbody>
        """.trimIndent()
    }

    private fun syntheticForumHtml(
        rows: List<String>,
        currentPage: Int,
        lastPage: Int,
        loggedIn: Boolean = true,
    ): String {
        val pagesHtml = if (lastPage > 1 || currentPage > 1) {
            """
            <div class="pages_btns"><div class="pages">
              <strong>$currentPage</strong>
              <a href="forumdisplay.php?fid=2&amp;page=$lastPage" class="last">... $lastPage</a>
            </div></div>
            """.trimIndent()
        } else {
            ""
        }
        val body = """
            $pagesHtml
            <table class="datatable">
              ${rows.joinToString("\n")}
            </table>
        """.trimIndent()
        return if (loggedIn) {
            loggedInHtml(body = body)
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
            <body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              $body
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun loggedInHtml(
        uid: String = "10000",
        username: String = "HiUser",
        logoutFormhash: String = "fh_logout_1",
        body: String = "",
    ): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu">
            <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
            <a href="logging.php?action=logout&amp;formhash=$logoutFormhash">退出</a>
          </div>
          $body
        </body>
        </html>
    """.trimIndent()

    private fun loggedOutHtml(message: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <div class="alert_error">$message</div>
        </body>
        </html>
    """.trimIndent()

    private fun gbkHtmlResponse(html: String, code: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "text/html; charset=gbk")
            .body(Buffer().write(html.toByteArray(TEST_GB18030)))
            .build()

    companion object {
        private val TEST_GB18030: Charset = Charset.forName("GB18030")
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private class FakeBlacklistRepository(
    initialBlocked: Set<String> = emptySet(),
) : BlacklistRepository {
    private val _blockedUsernames = MutableStateFlow(initialBlocked)
    override val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()

    private val _lastSyncedAtMs = MutableStateFlow<Long?>(null)
    override val lastSyncedAtMs: StateFlow<Long?> = _lastSyncedAtMs.asStateFlow()

    fun setBlocked(usernames: Set<String>) {
        _blockedUsernames.value = usernames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    override fun isBlocked(username: String): Boolean = username.trim() in _blockedUsernames.value

    override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
        BlacklistSyncResult.Success(
            snapshot = BlacklistSnapshot(usernames = _blockedUsernames.value),
            isEmptyConfirmed = _blockedUsernames.value.isEmpty(),
        )

    override suspend fun addToBlacklist(
        username: String,
        formhash: String?,
        priority: AccessPriority,
    ): BlacklistMutationResult {
        val next = _blockedUsernames.value + username.trim()
        _blockedUsernames.value = next
        return BlacklistMutationResult.Success(username.trim(), next)
    }

    override suspend fun removeFromBlacklist(
        username: String,
        formhash: String?,
        priority: AccessPriority,
    ): BlacklistMutationResult {
        val next = _blockedUsernames.value - username.trim()
        _blockedUsernames.value = next
        return BlacklistMutationResult.Success(username.trim(), next)
    }
}

private class FakeThreadListSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = _settings.asStateFlow()

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

private class FakeThreadListAuthRepository(
    initialAccount: SavedAccount? = null,
) : AuthRepository {
    private val _currentAccount = MutableStateFlow(initialAccount)
    override val currentAccount = _currentAccount.asStateFlow()

    private val _savedAccounts = MutableStateFlow(listOfNotNull(initialAccount))
    override val savedAccounts = _savedAccounts.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(
        if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
    )
    override val authState = _authState.asStateFlow()

    @Volatile
    private var epoch: Long = 1L
    override val sessionEpoch: Long get() = epoch

    @Volatile
    var nextReloginResult: SilentReloginResult = SilentReloginResult.NotLoggedIn

    @Volatile
    var onTrySilentRelogin: (suspend (Long, AccessPriority) -> SilentReloginResult)? = null

    fun setActiveAccount(account: SavedAccount?) {
        _currentAccount.value = account
        if (account != null) {
            _savedAccounts.update { list -> (list.filterNot { it.uid == account.uid } + account) }
            _authState.value = AuthState.SignedIn(account)
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    fun bumpEpoch(): Long {
        epoch += 1L
        return epoch
    }

    override suspend fun login(
        username: String,
        passwordOrMd5: String,
        securityQuestionId: Int,
        securityAnswer: String,
    ): LoginResult = LoginResult.Failure("Not used")

    override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("Not used")

    override suspend fun logout() {
        setActiveAccount(null)
    }

    override suspend fun removeAccount(uid: String) {
        _savedAccounts.update { list -> list.filterNot { it.uid == uid } }
        if (_currentAccount.value?.uid == uid) setActiveAccount(null)
    }

    override suspend fun trySilentRelogin(
        failedEpoch: Long,
        priority: AccessPriority,
    ): SilentReloginResult {
        val handler = onTrySilentRelogin
        return if (handler != null) handler(failedEpoch, priority) else nextReloginResult
    }
}
