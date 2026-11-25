package net.jejer.hipda.data.favorite

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.io.File
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
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.DefaultSearchRepository
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.di.FavoriteCacheDataStore
import net.jejer.hipda.di.SearchAndFavoriteModule
import net.jejer.hipda.di.SearchHistoryDataStore
import net.jejer.hipda.parser.LegacyTestSupport
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FavoriteRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: FavoriteTestSettingsRepository
    private lateinit var authRepository: FavoriteTestAuthRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    private val accountA = SavedAccount(
        uid = "10000",
        username = "HiUser",
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 2_000L,
    )
    private val accountB = SavedAccount(
        uid = "20002",
        username = "Bob",
        passwordMd5 = "fedcba9876543210fedcba9876543210",
        lastLoginAt = 1_000L,
    )

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        settingsRepository = FavoriteTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = FavoriteTestAuthRepository(initialAccount = accountA)
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

    private fun createDataStore(
        file: File = File(tempFolder.newFolder(), "favorite_cache.preferences_pb"),
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
    )

    private fun createRepository(
        store: FavoriteCacheStore = FavoriteCacheStore(createDataStore()),
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): Pair<DefaultFavoriteRepository, FavoriteCacheStore> {
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
        val repo = DefaultFavoriteRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            favoriteCacheStore = store,
            clock = { nowMs.get() },
            scope = scope,
        )
        return repo to store
    }

    @Test
    fun getList_realSampleParsesFavoritesMergesCacheAndSupportsAttentionPagination() = runBlocking {
        val sampleHtml = LegacyTestSupport.loadSample("simple_list/my_favorites.html")
        val attentionPage2Html = favoriteListHtml(
            uid = accountA.uid,
            username = accountA.username,
            kind = FavoriteKind.ATTENTION,
            tids = listOf("91001", "91002"),
            currentPage = 2,
            lastPage = 2,
        )
        server.enqueue(favoriteTestGbkHtmlResponse(sampleHtml))
        server.enqueue(favoriteTestGbkHtmlResponse(attentionPage2Html))

        val (repo, store) = createRepository()

        val favResult = repo.getList(kind = FavoriteKind.FAVORITE, page = 1)
        assertTrue("Expected Success, got $favResult", favResult is FavoriteListResult.Success)
        val favSuccess = favResult as FavoriteListResult.Success
        assertEquals(FavoriteKind.FAVORITE, favSuccess.kind)
        assertEquals(1, favSuccess.page)
        assertEquals(4, favSuccess.maxPage)
        assertTrue(favSuccess.hasNextPage)
        assertEquals("00000000", favSuccess.formhash)
        assertEquals(75, favSuccess.items.size)
        assertEquals("3468951", favSuccess.items[0].tid)
        assertEquals("推荐一个AI短剧：《持械入宋》，拍的很棒", favSuccess.items[0].title)

        // Merged into cache and StateFlow without polluting attentionTids
        assertEquals(75, repo.favoriteTids.value.size)
        assertTrue(repo.isFavorite("3468951"))
        assertTrue(repo.isFavorite("3149924"))
        assertTrue(repo.attentionTids.value.isEmpty())
        assertEquals(75, store.read(accountA.uid).favoriteTids.size)

        val attResult = repo.getList(kind = FavoriteKind.ATTENTION, page = 2)
        assertTrue("Expected Success, got $attResult", attResult is FavoriteListResult.Success)
        val attSuccess = attResult as FavoriteListResult.Success
        assertEquals(2, attSuccess.page)
        assertFalse(attSuccess.hasNextPage)
        assertEquals(setOf("91001", "91002"), repo.attentionTids.value)
        assertTrue(repo.isAttention("91001"))
        assertEquals(75, repo.favoriteTids.value.size)

        assertEquals("/forum/my.php?item=favorites&type=thread", server.takeRequest().target)
        assertEquals("/forum/my.php?item=attention&type=thread&page=2", server.takeRequest().target)
    }

    @Test
    fun syncCache_capsAtMaxThreePagesAndPreservesExistingCacheOnMidwayFailure() = runBlocking {
        val (repo, store) = createRepository()

        // 1. 4-page favorites list capped at default maxPages = 3 -> overwrites cache cleanly
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, listOf("101", "102"), 1, 4),
            ),
        )
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, listOf("103", "104"), 2, 4),
            ),
        )
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, listOf("105"), 3, 4),
            ),
        )

        val sync1 = repo.syncCache(FavoriteKind.FAVORITE)
        assertTrue("Expected Success, got $sync1", sync1 is FavoriteSyncResult.Success)
        val s1 = sync1 as FavoriteSyncResult.Success
        assertEquals(3, s1.pagesSynced)
        assertEquals(4, s1.lastPage)
        assertEquals(setOf("101", "102", "103", "104", "105"), s1.tids)
        assertEquals(setOf("101", "102", "103", "104", "105"), repo.favoriteTids.value)
        assertEquals(setOf("101", "102", "103", "104", "105"), store.read(accountA.uid).favoriteTids)

        assertEquals("/forum/my.php?item=favorites&type=thread", server.takeRequest().target)
        assertEquals("/forum/my.php?item=favorites&type=thread&page=2", server.takeRequest().target)
        assertEquals("/forum/my.php?item=favorites&type=thread&page=3", server.takeRequest().target)

        // 2. Midway failure on page 2 (HTTP 500) -> MUST preserve previous cache untouched
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, listOf("201"), 1, 3),
            ),
        )
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())

        val failedMidway = repo.syncCache(FavoriteKind.FAVORITE)
        assertTrue("Expected NetworkError on page 2 failure, got $failedMidway", failedMidway is FavoriteError.NetworkError)
        assertEquals(setOf("101", "102", "103", "104", "105"), repo.favoriteTids.value)
        assertEquals(setOf("101", "102", "103", "104", "105"), store.read(accountA.uid).favoriteTids)

        // 3. Truncated/malformed HTML page -> InvalidPage, MUST NOT wipe existing cache
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteLoggedInHtml(accountA.uid, accountA.username, body = """<div id="broken">截断页</div>"""),
            ),
        )
        val brokenPage = repo.syncCache(FavoriteKind.FAVORITE)
        assertTrue("Expected InvalidPage, got $brokenPage", brokenPage is FavoriteError.InvalidPage)
        assertEquals(setOf("101", "102", "103", "104", "105"), repo.favoriteTids.value)

        // 4. Confirmed empty favorites table -> cleanly clears cache
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, emptyList(), 1, 1),
            ),
        )
        val confirmedEmpty = repo.syncCache(FavoriteKind.FAVORITE)
        assertTrue("Expected Success on confirmed empty page, got $confirmedEmpty", confirmedEmpty is FavoriteSyncResult.Success)
        assertEquals(emptySet<String>(), repo.favoriteTids.value)
        assertEquals(emptySet<String>(), store.read(accountA.uid).favoriteTids)

        // 5. Two-page list stops early at lastPage = 2 instead of fetching page 3
        val countBefore = server.requestCount
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.ATTENTION, listOf("501"), 1, 2),
            ),
        )
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.ATTENTION, listOf("502"), 2, 2),
            ),
        )
        val twoPageSync = repo.syncCache(FavoriteKind.ATTENTION, maxPages = 3)
        assertTrue("Expected Success, got $twoPageSync", twoPageSync is FavoriteSyncResult.Success)
        val twoPageSuccess = twoPageSync as FavoriteSyncResult.Success
        assertEquals(2, twoPageSuccess.pagesSynced)
        assertEquals(2, twoPageSuccess.lastPage)
        assertEquals(setOf("501", "502"), repo.attentionTids.value)
        assertEquals(2, server.requestCount - countBefore)
    }

    @Test
    fun addRemoveAndUndo_inAjaxXmlDoesNotTriggerFalseReloginAndUpdatesStateFlowAtomically() = runBlocking {
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            SilentReloginResult.Recovered(authRepository.bumpEpoch())
        }

        val (repo, store) = createRepository()

        // 1. Add favorite -> normal inajax XML (no #umenu)
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已成功添加到收藏夹中<br /><a href="my.php?item=favorites&amp;type=thread">[查看我收藏的所有主题]</a><script type="text/javascript" reload="1">hideWindow('fav');</script>]]></root>""",
            ),
        )
        // 2. Duplicate/idempotent add -> "您曾经收藏过这个主题"
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[您曾经收藏过这个主题<br /><a href="my.php?item=favorites&amp;type=thread">[查看]</a>]]></root>""",
            ),
        )
        // 3. Remove favorite (Snackbar undo or user un-favorite)
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已从您的收藏夹中移除<script>hideWindow('fav');</script>]]></root>""",
            ),
        )
        // 4. Undo removal (re-add)
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已成功添加到收藏夹中]]></root>""",
            ),
        )

        repo.favoriteTids.test {
            assertEquals(emptySet<String>(), awaitItem())

            val addRes = repo.add(FavoriteKind.FAVORITE, "3468951")
            assertTrue("Expected Success, got $addRes", addRes is FavoriteMutationResult.Success)
            val addSuccess = addRes as FavoriteMutationResult.Success
            assertEquals("此主题已成功添加到收藏夹中", addSuccess.message)
            assertFalse(addSuccess.alreadyApplied)
            assertEquals(setOf("3468951"), awaitItem())
            assertTrue(repo.isFavorite("3468951"))

            // Idempotent already-favorited message succeeds without error
            val dupRes = repo.add(FavoriteKind.FAVORITE, "3468951")
            assertTrue("Expected idempotent Success, got $dupRes", dupRes is FavoriteMutationResult.Success)
            assertTrue((dupRes as FavoriteMutationResult.Success).alreadyApplied)
            assertEquals("您曾经收藏过这个主题", dupRes.message)

            // Remove emits empty set immediately
            val removeRes = repo.remove(FavoriteKind.FAVORITE, "3468951")
            assertTrue("Expected remove Success, got $removeRes", removeRes is FavoriteMutationResult.Success)
            assertEquals(emptySet<String>(), awaitItem())
            assertFalse(repo.isFavorite("3468951"))

            // Undo re-adds immediately
            val undoRes = repo.add(FavoriteKind.FAVORITE, "3468951")
            assertTrue("Expected undo Success, got $undoRes", undoRes is FavoriteMutationResult.Success)
            assertEquals(setOf("3468951"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // Normal inajax=1 XML must NEVER trigger false silent relogin!
        assertEquals(0, reloginCalls.get())
        assertEquals(setOf("3468951"), store.read(accountA.uid).favoriteTids)

        assertEquals(
            "/forum/my.php?item=favorites&action=add&inajax=1&ajaxtarget=favorite_msg&tid=3468951",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/my.php?item=favorites&action=add&inajax=1&ajaxtarget=favorite_msg&tid=3468951",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/my.php?item=favorites&action=remove&inajax=1&ajaxtarget=favorite_msg&tid=3468951",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/my.php?item=favorites&action=add&inajax=1&ajaxtarget=favorite_msg&tid=3468951",
            server.takeRequest().target,
        )
    }

    @Test
    fun addAndRemove_failuresAndUnauthXmlNeverDirtyCacheAndSupportReloginRecovery() = runBlocking {
        val (repo, store) = createRepository()
        store.add(accountA.uid, FavoriteKind.ATTENTION, "55555")
        withTimeout(3.seconds) {
            repo.attentionTids.first { it == setOf("55555") }
        }

        // 1. Invalid tids rejected with 0 requests
        for (badTid in listOf("", "   ", "0", "-1", "+123", "abc", "123&x=1")) {
            assertTrue(repo.add(FavoriteKind.FAVORITE, badTid) is FavoriteError.InvalidInput)
            assertTrue(repo.remove(FavoriteKind.FAVORITE, badTid) is FavoriteError.InvalidInput)
        }
        assertEquals(0, server.requestCount)

        // 2. Site error XML ("您的收藏夹已满") -> SiteError, cache untouched
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您的收藏夹已满，请在删除部分收藏后再试]]></root>""",
            ),
        )
        val fullErr = repo.add(FavoriteKind.FAVORITE, "77777")
        assertTrue("Expected SiteError, got $fullErr", fullErr is FavoriteError.SiteError)
        assertFalse(repo.isFavorite("77777"))

        // 3. Site error XML containing "已被删除" inside "主题不存在或已被删除" -> SiteError, NOT false remove success!
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[指定的主题不存在或已被删除]]></root>""",
            ),
        )
        val notExistErr = repo.remove(FavoriteKind.ATTENTION, "55555")
        assertTrue("Expected SiteError, got $notExistErr", notExistErr is FavoriteError.SiteError)
        assertTrue("Cache must remain untouched on error", repo.isAttention("55555"))

        // 4. Unauth XML with suppressed relogin -> AuthRequired, cache untouched
        authRepository.nextReloginResult = SilentReloginResult.Suppressed
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[您还未登录，无法进行当前操作]]></root>""",
            ),
        )
        val authErr = repo.add(FavoriteKind.ATTENTION, "88888")
        assertTrue("Expected AuthRequired, got $authErr", authErr is FavoriteError.AuthRequired)
        assertEquals(AuthRequiredReason.MANUALLY_LOGGED_OUT, (authErr as FavoriteError.AuthRequired).reason)
        assertFalse(repo.isAttention("88888"))

        // 5. Unauth XML with successful silent relogin -> retries once and succeeds
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            SilentReloginResult.Recovered(authRepository.bumpEpoch())
        }
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[您还未登录，无法进行此操作]]></root>""",
            ),
        )
        server.enqueue(
            favoriteTestGbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已成功添加到关注列表中]]></root>""",
            ),
        )
        val recoveredAdd = repo.add(FavoriteKind.ATTENTION, "88888")
        assertTrue("Expected Success after silent relogin, got $recoveredAdd", recoveredAdd is FavoriteMutationResult.Success)
        assertEquals(1, reloginCalls.get())
        assertEquals(setOf("55555", "88888"), repo.attentionTids.value)

        // 6. Optional form-based delete (removeByForm) posts GBK form and updates cache
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteLoggedInHtml(accountA.uid, accountA.username, body = """<div class="alert_info">已删除</div>"""),
            ),
        )
        val formDelete = repo.removeByForm(FavoriteKind.ATTENTION, "55555", formhash = "fh_del_1")
        assertTrue("Expected form delete Success, got $formDelete", formDelete is FavoriteMutationResult.Success)
        assertEquals(setOf("88888"), repo.attentionTids.value)

        // Inspect exact request count (5 previous requests + 1 POST form request)
        repeat(5) { server.takeRequest() }
        val lastReq = server.takeRequest()
        assertEquals("POST", lastReq.method)
        assertEquals("/forum/my.php?item=attention&type=thread", lastReq.target)
        val formMap = decodeGbkForm(lastReq.body?.utf8().orEmpty())
        assertEquals("true", formMap["attentionsubmit"])
        assertEquals("55555", formMap["delete[]"])
        assertEquals("fh_del_1", formMap["formhash"])
    }

    @Test
    fun getListAndSyncCache_rejectServerUmenuUidMismatchWithoutPollutingCache() = runBlocking {
        val (repo, store) = createRepository()
        store.replace(accountA.uid, FavoriteKind.FAVORITE, listOf("1001"), syncedAtMs = 1_000L)
        withTimeout(3.seconds) {
            repo.favoriteTids.first { it == setOf("1001") }
        }

        // Local active account is accountA (10000), but server #umenu belongs to accountB (20002)
        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountB.uid, accountB.username, FavoriteKind.FAVORITE, listOf("8888"), 1, 1),
            ),
        )
        val listMismatch = repo.getList(FavoriteKind.FAVORITE, page = 1)
        assertTrue("Expected AuthRequired on getList UID mismatch, got $listMismatch", listMismatch is FavoriteError.AuthRequired)
        assertEquals(AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED, (listMismatch as FavoriteError.AuthRequired).reason)
        assertEquals(setOf("1001"), repo.favoriteTids.value)
        assertEquals(setOf("1001"), store.read(accountA.uid).favoriteTids)
        assertEquals(emptySet<String>(), store.read(accountB.uid).favoriteTids)

        server.enqueue(
            favoriteTestGbkHtmlResponse(
                favoriteListHtml(accountB.uid, accountB.username, FavoriteKind.FAVORITE, listOf("8888"), 1, 1),
            ),
        )
        val syncMismatch = repo.syncCache(FavoriteKind.FAVORITE)
        assertTrue("Expected AuthRequired on syncCache UID mismatch, got $syncMismatch", syncMismatch is FavoriteError.AuthRequired)
        assertEquals(setOf("1001"), repo.favoriteTids.value)
        assertEquals(setOf("1001"), store.read(accountA.uid).favoriteTids)
    }

    @Test
    fun accountSwitchLogoutAndInFlightRace_strictlyIsolateBuckets() = runBlocking {
        val (repo, store) = createRepository()

        // Seed Account A
        store.replace(accountA.uid, FavoriteKind.FAVORITE, listOf("1001"), syncedAtMs = 1_000L)
        store.replace(accountA.uid, FavoriteKind.ATTENTION, listOf("2001"), syncedAtMs = 1_000L)
        withTimeout(3.seconds) {
            repo.favoriteTids.first { it == setOf("1001") }
            repo.attentionTids.first { it == setOf("2001") }
        }

        // Seed Account B on disk
        store.replace(accountB.uid, FavoriteKind.FAVORITE, listOf("3001"), syncedAtMs = 2_000L)

        // Switch to Account B -> immediately switches bucket without leaking Account A
        authRepository.setActiveAccount(accountB)
        withTimeout(3.seconds) {
            repo.favoriteTids.first { it == setOf("3001") }
            repo.attentionTids.first { it.isEmpty() }
        }
        assertTrue(repo.isFavorite("3001"))
        assertFalse(repo.isFavorite("1001"))
        assertFalse(repo.isAttention("2001"))

        // Logout -> immediately clears in-memory sets and rejects mutations with zero requests
        authRepository.logout()
        withTimeout(3.seconds) {
            repo.favoriteTids.first { it.isEmpty() }
            repo.attentionTids.first { it.isEmpty() }
        }
        assertFalse(repo.isFavorite("3001"))
        val signedOutAdd = repo.add(FavoriteKind.FAVORITE, "99999")
        assertTrue(signedOutAdd is FavoriteError.AuthRequired)
        assertEquals(0, server.requestCount)

        // Switch back to Account A -> restores Account A's persisted bucket
        authRepository.setActiveAccount(accountA)
        withTimeout(3.seconds) {
            repo.favoriteTids.first { it == setOf("1001") }
            repo.attentionTids.first { it == setOf("2001") }
        }

        // In-flight sync started under Account A completing after switching to Account B must NOT pollute Account B
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return favoriteTestGbkHtmlResponse(
                    favoriteListHtml(accountA.uid, accountA.username, FavoriteKind.FAVORITE, listOf("999111"), 1, 1),
                )
            }
        }
        val staleSyncJob = async(Dispatchers.Default) { repo.syncCache(FavoriteKind.FAVORITE) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            authRepository.setActiveAccount(accountB)
            withTimeout(3.seconds) {
                repo.favoriteTids.first { it == setOf("3001") }
            }
            release.countDown()
            val staleResult = withTimeout(5.seconds) { staleSyncJob.await() }
            assertTrue("Expected AuthRequired on cross-account race, got $staleResult", staleResult is FavoriteError.AuthRequired)
            assertEquals(setOf("3001"), repo.favoriteTids.value)
            assertEquals(setOf("3001"), store.read(accountB.uid).favoriteTids)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun backgroundSilentPriority_abandonsChallengeWithoutInvokingSolver() = runBlocking {
        val solverCalls = AtomicInteger(0)
        val (repo, _) = createRepository(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Failed(AssertionError("ChallengeSolver must not run under BackgroundSilent"))
            },
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build()
        }

        val listErr = repo.getList(FavoriteKind.FAVORITE, priority = AccessPriority.BackgroundSilent)
        assertTrue(listErr is FavoriteError.ChallengeRequired && listErr.abandoned)

        val syncErr = repo.syncCache(FavoriteKind.FAVORITE, priority = AccessPriority.BackgroundSilent)
        assertTrue(syncErr is FavoriteError.ChallengeRequired && syncErr.abandoned)

        val addErr = repo.add(FavoriteKind.FAVORITE, "3468951", priority = AccessPriority.BackgroundSilent)
        assertTrue(addErr is FavoriteError.ChallengeRequired && addErr.abandoned)

        assertEquals(0, solverCalls.get())
    }

    @Test
    fun searchAndFavoriteModule_declaresExpectedHiltBindingsAndDataStoreFiles() {
        assertTrue(SearchAndFavoriteModule::class.java.isAnnotationPresent(Module::class.java))

        val bindSearch = SearchAndFavoriteModule::class.java.getDeclaredMethod(
            "bindSearchRepository",
            DefaultSearchRepository::class.java,
        )
        assertTrue(bindSearch.isAnnotationPresent(Binds::class.java))
        assertTrue(bindSearch.isAnnotationPresent(Singleton::class.java))
        assertEquals(SearchRepository::class.java, bindSearch.returnType)

        val bindFavorite = SearchAndFavoriteModule::class.java.getDeclaredMethod(
            "bindFavoriteRepository",
            DefaultFavoriteRepository::class.java,
        )
        assertTrue(bindFavorite.isAnnotationPresent(Binds::class.java))
        assertTrue(bindFavorite.isAnnotationPresent(Singleton::class.java))
        assertEquals(FavoriteRepository::class.java, bindFavorite.returnType)

        val provideSearchHistory = SearchAndFavoriteModule.Companion::class.java.getDeclaredMethod(
            "provideSearchHistoryDataStore",
            Context::class.java,
        )
        assertTrue(provideSearchHistory.isAnnotationPresent(Provides::class.java))
        assertTrue(provideSearchHistory.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideSearchHistory.isAnnotationPresent(SearchHistoryDataStore::class.java))
        assertEquals(DataStore::class.java, provideSearchHistory.returnType)

        val provideFavoriteCache = SearchAndFavoriteModule.Companion::class.java.getDeclaredMethod(
            "provideFavoriteCacheDataStore",
            Context::class.java,
        )
        assertTrue(provideFavoriteCache.isAnnotationPresent(Provides::class.java))
        assertTrue(provideFavoriteCache.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideFavoriteCache.isAnnotationPresent(FavoriteCacheDataStore::class.java))
        assertEquals(DataStore::class.java, provideFavoriteCache.returnType)

        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals("search_history.preferences_pb", SearchAndFavoriteModule.searchHistoryDataStoreFile(context).name)
        assertEquals("favorite_cache.preferences_pb", SearchAndFavoriteModule.favoriteCacheDataStoreFile(context).name)
    }

    private fun favoriteListHtml(
        uid: String,
        username: String,
        kind: FavoriteKind,
        tids: List<String>,
        currentPage: Int,
        lastPage: Int,
        formhash: String = "fh_fav_1",
    ): String {
        val rows = tids.joinToString("\n") { tid ->
            """
            <tr>
              <td><input class="checkbox" type="checkbox" name="delete[]" value="$tid"></td>
              <th><a href="viewthread.php?tid=$tid&amp;from=${kind.itemParam}">主题 $tid</a></th>
              <td class="forum"><a href="forumdisplay.php?fid=2">Discovery</a></td>
              <td class="lastpost">2026-11-25 12:00</td>
            </tr>
            """.trimIndent()
        }
        val pages = buildString {
            for (p in 1..lastPage) {
                if (p == currentPage) {
                    append("<strong>$p</strong>")
                } else {
                    append("""<a href="my.php?item=${kind.itemParam}&amp;type=thread&amp;page=$p">$p</a>""")
                }
            }
        }
        return favoriteLoggedInHtml(
            uid = uid,
            username = username,
            body = """
                <form method="post" action="my.php?item=${kind.itemParam}&amp;type=thread">
                  <input type="hidden" name="formhash" value="$formhash">
                  <table class="datatable">
                    <tbody>$rows</tbody>
                  </table>
                </form>
                <div class="pages">$pages</div>
            """.trimIndent(),
        )
    }

    private fun decodeGbkForm(rawBody: String): Map<String, String> {
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

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private val FAVORITE_TEST_GB18030: Charset = Charset.forName("GB18030")

private fun favoriteTestGbkHtmlResponse(html: String, code: Int = 200): MockResponse =
    MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "text/html; charset=gbk")
        .body(Buffer().write(html.toByteArray(FAVORITE_TEST_GB18030)))
        .build()

private fun favoriteTestGbkXmlResponse(xml: String, code: Int = 200): MockResponse =
    MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "text/xml; charset=gbk")
        .body(Buffer().write(xml.toByteArray(FAVORITE_TEST_GB18030)))
        .build()

private fun favoriteLoggedInHtml(
    uid: String,
    username: String,
    body: String,
): String = """
    <!DOCTYPE html>
    <html>
    <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
    <body>
      <div id="umenu">
        <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
        <a href="logging.php?action=logout&amp;formhash=00000000">退出</a>
      </div>
      $body
    </body>
    </html>
""".trimIndent()

private class FavoriteTestSettingsRepository(
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

private class FavoriteTestAuthRepository(
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
            _savedAccounts.update { list -> list.filterNot { it.uid == account.uid } + account }
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
    ): LoginResult = LoginResult.Failure("Not used in unit test")

    override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("Not used in unit test")

    override suspend fun logout() {
        setActiveAccount(null)
    }

    override suspend fun removeAccount(uid: String) {
        _savedAccounts.update { list -> list.filterNot { it.uid == uid } }
        if (_currentAccount.value?.uid == uid) {
            setActiveAccount(null)
        }
    }

    override suspend fun trySilentRelogin(
        failedEpoch: Long,
        priority: AccessPriority,
    ): SilentReloginResult {
        val handler = onTrySilentRelogin
        return if (handler != null) {
            handler(failedEpoch, priority)
        } else {
            nextReloginResult
        }
    }
}
