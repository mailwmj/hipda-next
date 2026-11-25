package net.jejer.hipda.data.user

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpClient
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
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.di.BlacklistDataStore
import net.jejer.hipda.di.UserModule
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class BlacklistRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: UserTestSettingsRepository
    private lateinit var authRepository: UserTestAuthRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    private val accountA = SavedAccount(
        uid = "10001",
        username = "Alice",
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 2_000L,
    )
    private val accountB = SavedAccount(
        uid = "10002",
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
        settingsRepository = UserTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = UserTestAuthRepository(initialAccount = accountA)
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

    private fun createDataStore(file: File = File(tempFolder.newFolder(), "blacklist_cache.preferences_pb")): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() },
            ),
            scope = scope,
            produceFile = { file },
        )

    private fun createRepository(
        store: BlacklistStore = BlacklistStore(createDataStore()),
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): Pair<DefaultBlacklistRepository, BlacklistStore> {
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
        val repo = DefaultBlacklistRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            blacklistStore = store,
            clock = { nowMs.get() },
            scope = scope,
        )
        return repo to store
    }

    @Test
    fun syncBlacklist_populatedAndConfirmedEmptyPagesUpdateStateFlowAndPersistCache() = runBlocking {
        val (repo, store) = createRepository()

        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    blockedUsers = listOf("Spammer1", "测试黑名单", "Spammer1"),
                ),
            ),
        )

        val sync1 = repo.syncBlacklist()
        assertTrue("Expected Success, got $sync1", sync1 is BlacklistSyncResult.Success)
        val success1 = sync1 as BlacklistSyncResult.Success
        assertFalse(success1.isEmptyConfirmed)
        assertEquals(setOf("Spammer1", "测试黑名单"), success1.usernames)
        assertEquals(1_700_000_000_000L, success1.lastSyncedAtMs)
        assertEquals(setOf("Spammer1", "测试黑名单"), repo.blockedUsernames.value)
        assertEquals(1_700_000_000_000L, repo.lastSyncedAtMs.value)
        assertTrue(repo.isBlocked("Spammer1"))
        assertTrue(repo.isBlocked("  测试黑名单  "))
        assertFalse(repo.isBlocked("NotBlocked"))
        assertEquals(setOf("Spammer1", "测试黑名单"), store.read(accountA.uid).usernames)
        assertEquals("/forum/pm.php?action=viewblack", server.takeRequest().target)

        // Confirmed empty blacklist ("暂无数据") MUST clear local cache and advance sync time
        nowMs.set(1_700_000_050_000L)
        server.enqueue(userTestGbkHtmlResponse(emptyConfirmedViewBlackHtml(accountA.uid, accountA.username)))

        val sync2 = repo.syncBlacklist()
        assertTrue("Expected Success on confirmed empty, got $sync2", sync2 is BlacklistSyncResult.Success)
        val success2 = sync2 as BlacklistSyncResult.Success
        assertTrue(success2.isEmptyConfirmed)
        assertEquals(emptySet<String>(), success2.usernames)
        assertEquals(1_700_000_050_000L, success2.lastSyncedAtMs)
        assertEquals(emptySet<String>(), repo.blockedUsernames.value)
        assertEquals(1_700_000_050_000L, repo.lastSyncedAtMs.value)
        assertFalse(repo.isBlocked("Spammer1"))
        assertEquals(emptySet<String>(), store.read(accountA.uid).usernames)
    }

    @Test
    fun syncBlacklist_invalidPageSiteErrorHttpErrorAndAuthFailureNeverClearLocalCache() = runBlocking {
        val (repo, store) = createRepository()

        // Seed initial confirmed cache
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("KeepMe1", "KeepMe2")),
            ),
        )
        assertTrue(repo.syncBlacklist() is BlacklistSyncResult.Success)
        val initialSyncTime = repo.lastSyncedAtMs.value

        nowMs.addAndGet(10_000L)

        // 1. div.blacklist present but empty without "暂无数据" -> InvalidPage("黑名单数据解析错误")
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div class="blacklist"><ul class="commonlist"></ul></div>""",
                ),
            ),
        )
        val invalidData = repo.syncBlacklist()
        assertTrue("Expected InvalidPage, got $invalidData", invalidData is UserError.InvalidPage)
        assertEquals(setOf("KeepMe1", "KeepMe2"), repo.blockedUsernames.value)
        assertEquals(initialSyncTime, repo.lastSyncedAtMs.value)

        // 2. Missing div.blacklist entirely -> InvalidPage("页面解析错误")
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div id="broken">截断页面</div>""",
                ),
            ),
        )
        val missingDiv = repo.syncBlacklist()
        assertTrue("Expected InvalidPage, got $missingDiv", missingDiv is UserError.InvalidPage)
        assertEquals(setOf("KeepMe1", "KeepMe2"), repo.blockedUsernames.value)

        // 3. Page containing div.alert_error even with div.blacklist -> SiteError
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div class="alert_error">论坛维护中<a href="index.php">返回</a></div><div class="blacklist"><p class="nodata">暂无数据</p></div>""",
                ),
            ),
        )
        val siteError = repo.syncBlacklist()
        assertEquals(UserError.SiteError("论坛维护中"), siteError)
        assertEquals(setOf("KeepMe1", "KeepMe2"), repo.blockedUsernames.value)

        // 4. HTTP 500 -> NetworkError
        server.enqueue(MockResponse.Builder().code(500).body("Internal Error").build())
        val httpError = repo.syncBlacklist()
        assertTrue("Expected NetworkError, got $httpError", httpError is UserError.NetworkError)
        assertEquals(setOf("KeepMe1", "KeepMe2"), repo.blockedUsernames.value)

        // 5. Auth failure -> AuthRequired
        authRepository.nextReloginResult = SilentReloginResult.Suppressed
        server.enqueue(userTestGbkHtmlResponse(userTestLoggedOutHtml("需要登录")))
        val authError = repo.syncBlacklist()
        assertTrue("Expected AuthRequired, got $authError", authError is UserError.AuthRequired)
        assertEquals(AuthRequiredReason.MANUALLY_LOGGED_OUT, (authError as UserError.AuthRequired).reason)
        assertEquals(setOf("KeepMe1", "KeepMe2"), repo.blockedUsernames.value)
        assertEquals(setOf("KeepMe1", "KeepMe2"), store.read(accountA.uid).usernames)
        assertEquals(initialSyncTime, store.read(accountA.uid).lastSyncedAtMs)
    }

    @Test
    fun addRemoveAndUndoBlacklist_encodesGbkChineseUsernameAndEmitsReactiveStateFlowSequence() = runBlocking {
        val (repo, _) = createRepository()

        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "添加成功")))
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "移除成功")))
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "撤销重加成功")))

        repo.blockedUsernames.test {
            assertEquals(emptySet<String>(), awaitItem())

            // 1. Add Chinese username with explicit formhash
            val addResult = repo.addToBlacklist(username = " 测试用户 ", formhash = "fh_explicit_1")
            assertTrue("Expected Success, got $addResult", addResult is BlacklistMutationResult.Success)
            assertEquals("测试用户", (addResult as BlacklistMutationResult.Success).username)
            assertEquals(setOf("测试用户"), awaitItem())
            assertTrue(repo.isBlocked("测试用户"))

            // 2. Remove username
            val removeResult = repo.removeFromBlacklist(username = "测试用户", formhash = "fh_explicit_2")
            assertTrue("Expected Success, got $removeResult", removeResult is BlacklistMutationResult.Success)
            assertEquals(emptySet<String>(), awaitItem())
            assertFalse(repo.isBlocked("测试用户"))

            // 3. Undo remove -> re-add
            val undoResult = repo.addToBlacklist(username = "测试用户", formhash = "fh_explicit_3")
            assertTrue("Expected Success, got $undoResult", undoResult is BlacklistMutationResult.Success)
            assertEquals(setOf("测试用户"), awaitItem())
            assertTrue(repo.isBlocked("测试用户"))

            cancelAndIgnoreRemainingEvents()
        }

        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/forum/pm.php?action=addblack", req1.target)
        assertTrue(req1.headers["Content-Type"].orEmpty().contains("charset=GBK", ignoreCase = true))
        val rawBody1 = req1.body?.utf8().orEmpty()
        assertTrue(
            "Expected GBK percent-encoded Chinese username '%B2%E2%CA%D4%D3%C3%BB%A7' in body, got: $rawBody1",
            rawBody1.contains("user=%B2%E2%CA%D4%D3%C3%BB%A7"),
        )
        val fields1 = decodeGbkForm(rawBody1)
        assertEquals("fh_explicit_1", fields1["formhash"])
        assertEquals("测试用户", fields1["user"])

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/forum/pm.php?action=delblack", req2.target)
        val fields2 = decodeGbkForm(req2.body?.utf8().orEmpty())
        assertEquals("fh_explicit_2", fields2["formhash"])
        assertEquals("测试用户", fields2["user"])

        val req3 = server.takeRequest()
        assertEquals("POST", req3.method)
        assertEquals("/forum/pm.php?action=addblack", req3.target)
        val fields3 = decodeGbkForm(req3.body?.utf8().orEmpty())
        assertEquals("fh_explicit_3", fields3["formhash"])
        assertEquals("测试用户", fields3["user"])
    }

    @Test
    fun addAndRemoveBlacklist_autoPrefetchesFormhashWhenMissingOrBlank() = runBlocking {
        // 1. addToBlacklist with null formhash on cold cache -> GET viewblack -> POST addblack
        val (repoForAdd, _) = createRepository()
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, emptyList(), formhashInput = "fh_fetched_add"),
            ),
        )
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "添加成功")))

        val addResult = repoForAdd.addToBlacklist(username = "AutoHashUser", formhash = null)
        assertTrue("Expected Success, got $addResult", addResult is BlacklistMutationResult.Success)
        assertTrue(repoForAdd.isBlocked("AutoHashUser"))

        val getForAdd = server.takeRequest()
        assertEquals("GET", getForAdd.method)
        assertEquals("/forum/pm.php?action=viewblack", getForAdd.target)
        val postAdd = server.takeRequest()
        assertEquals("POST", postAdd.method)
        assertEquals("/forum/pm.php?action=addblack", postAdd.target)
        assertEquals("fh_fetched_add", decodeGbkForm(postAdd.body?.utf8().orEmpty())["formhash"])

        // 2. removeFromBlacklist with blank formhash on cold cache -> GET viewblack -> POST delblack
        val (repoForDel, storeForDel) = createRepository()
        storeForDel.replace(accountA.uid, listOf("AutoHashUser"), syncedAtMs = 1_000L)
        withTimeout(3.seconds) {
            repoForDel.blockedUsernames.first { "AutoHashUser" in it }
        }
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("AutoHashUser"), formhashInput = "fh_fetched_del"),
            ),
        )
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "移除成功")))

        val removeResult = repoForDel.removeFromBlacklist(username = "AutoHashUser", formhash = "   ")
        assertTrue("Expected Success, got $removeResult", removeResult is BlacklistMutationResult.Success)
        assertFalse(repoForDel.isBlocked("AutoHashUser"))

        val getForDel = server.takeRequest()
        assertEquals("GET", getForDel.method)
        assertEquals("/forum/pm.php?action=viewblack", getForDel.target)
        val postDel = server.takeRequest()
        assertEquals("POST", postDel.method)
        assertEquals("/forum/pm.php?action=delblack", postDel.target)
        assertEquals("fh_fetched_del", decodeGbkForm(postDel.body?.utf8().orEmpty())["formhash"])

        // 3. When prefetched viewblack lacks formhash on cold cache -> fail cleanly with InvalidPage and 0 POST requests
        val (repoForMissingHash, _) = createRepository()
        val countBeforeMissingHash = server.requestCount
        server.enqueue(
            userTestGbkHtmlResponse(
                """
                <html><body>
                  <div id="umenu"><cite><a href="space.php?uid=${accountA.uid}">${accountA.username}</a></cite></div>
                  <div class="blacklist"><p class="nodata">暂无数据</p></div>
                </body></html>
                """.trimIndent(),
            ),
        )
        val missingHashResult = repoForMissingHash.addToBlacklist(username = "NoHashTarget", formhash = null)
        assertTrue("Expected InvalidPage when formhash absent, got $missingHashResult", missingHashResult is UserError.InvalidPage)
        assertEquals("Should only issue GET viewblack without POST", countBeforeMissingHash + 1, server.requestCount)
        assertFalse(repoForMissingHash.isBlocked("NoHashTarget"))
    }

    @Test
    fun addAndRemoveBlacklist_alertErrorAndInvalidInputDoNotMutateLocalCache() = runBlocking {
        val (repo, store) = createRepository()

        // Blank username -> InvalidInput, zero network requests
        assertTrue(repo.addToBlacklist("   ", formhash = "fh1") is UserError.InvalidInput)
        assertTrue(repo.removeFromBlacklist("", formhash = "fh1") is UserError.InvalidInput)
        assertEquals(0, server.requestCount)

        // Seed existing entry
        store.replace(accountA.uid, listOf("ExistingTroll"), syncedAtMs = 500L)
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { "ExistingTroll" in it }
        }

        // Server rejects add with div.alert_error (including trailing <a> link text that must be stripped)
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div class="alert_error">对不起，您不能将自己加入黑名单<a href="javascript:history.back()">[ 点击这里返回上一页 ]</a></div>""",
                ),
            ),
        )
        val addRejected = repo.addToBlacklist(username = accountA.username, formhash = "fh1")
        assertEquals(UserError.SiteError("对不起，您不能将自己加入黑名单"), addRejected)
        assertEquals(setOf("ExistingTroll"), repo.blockedUsernames.value)
        assertFalse(repo.isBlocked(accountA.username))

        // Server rejects remove with div.alert_error -> existing blocked user MUST remain in cache
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div class="alert_error">您的请求来路不正确<a href="index.php">返回</a></div>""",
                ),
            ),
        )
        val removeRejected = repo.removeFromBlacklist(username = "ExistingTroll", formhash = "fh1")
        assertEquals(UserError.SiteError("您的请求来路不正确"), removeRejected)
        assertEquals(setOf("ExistingTroll"), repo.blockedUsernames.value)
        assertTrue(repo.isBlocked("ExistingTroll"))
    }

    @Test
    fun concurrentSyncAndMutation_areSerializedWithoutStaleSyncOverwritingAdd() = runBlocking {
        val (repo, _) = createRepository()

        val syncEntered = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val activeNetworkCalls = AtomicInteger(0)
        val maxConcurrentCalls = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val active = activeNetworkCalls.incrementAndGet()
                maxConcurrentCalls.accumulateAndGet(active, ::maxOf)
                try {
                    return when (request.target) {
                        "/forum/pm.php?action=viewblack" -> {
                            syncEntered.countDown()
                            releaseSync.await(5, TimeUnit.SECONDS)
                            userTestGbkHtmlResponse(
                                viewBlackHtml(accountA.uid, accountA.username, listOf("OldBlocked")),
                            )
                        }

                        "/forum/pm.php?action=addblack" -> {
                            userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "ok"))
                        }

                        else -> MockResponse.Builder().code(404).build()
                    }
                } finally {
                    activeNetworkCalls.decrementAndGet()
                }
            }
        }

        val syncJob = async(Dispatchers.Default) { repo.syncBlacklist() }
        assertTrue("syncBlacklist did not reach server", syncEntered.await(5, TimeUnit.SECONDS))

        val addJob = async(Dispatchers.Default) {
            repo.addToBlacklist(username = "FreshBlocked", formhash = "fh_concurrent")
        }

        try {
            // Brief yield while sync is still held; addJob must stay queued behind operationMutex
            Thread.sleep(50)
            assertEquals("Concurrent write must wait for in-flight sync mutex", 1, maxConcurrentCalls.get())
            releaseSync.countDown()

            val syncRes = withTimeout(5.seconds) { syncJob.await() }
            val addRes = withTimeout(5.seconds) { addJob.await() }
            assertTrue(syncRes is BlacklistSyncResult.Success)
            assertTrue(addRes is BlacklistMutationResult.Success)
            assertEquals(1, maxConcurrentCalls.get())
            assertEquals(setOf("OldBlocked", "FreshBlocked"), repo.blockedUsernames.value)
        } finally {
            releaseSync.countDown()
        }
    }

    @Test
    fun accountSwitchAndLogout_isolateCacheAndDropStaleCrossAccountInFlightSync() = runBlocking {
        val (repo, store) = createRepository()

        // Account A syncs SpammerA
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("SpammerA")),
            ),
        )
        assertTrue(repo.syncBlacklist() is BlacklistSyncResult.Success)
        assertEquals(setOf("SpammerA"), repo.blockedUsernames.value)
        assertTrue(repo.isBlocked("SpammerA"))

        // Switch to Account B -> Account A's blacklist must NOT leak into Account B
        authRepository.setActiveAccount(accountB)
        assertFalse("isBlocked must immediately isolate on account switch", repo.isBlocked("SpammerA"))
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it.isEmpty() }
            repo.lastSyncedAtMs.first { it == null }
        }

        // Account B adds SpammerB
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountB.uid, accountB.username, "ok")))
        assertTrue(repo.addToBlacklist("SpammerB", formhash = "fh_b") is BlacklistMutationResult.Success)
        assertEquals(setOf("SpammerB"), repo.blockedUsernames.value)
        assertFalse(repo.isBlocked("SpammerA"))
        assertTrue(repo.isBlocked("SpammerB"))

        // Logout -> StateFlow resets to empty & null sync time
        authRepository.logout()
        assertFalse(repo.isBlocked("SpammerB"))
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it.isEmpty() }
            repo.lastSyncedAtMs.first { it == null }
        }

        // Switch back to Account A -> restores Account A's own persisted cache without SpammerB
        authRepository.setActiveAccount(accountA)
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it == setOf("SpammerA") }
        }
        assertTrue(repo.isBlocked("SpammerA"))
        assertFalse(repo.isBlocked("SpammerB"))

        // In-flight sync started under Account A while switching to Account B must NOT pollute Account B's cache
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return userTestGbkHtmlResponse(
                    viewBlackHtml(accountA.uid, accountA.username, listOf("LateAccountABlocked")),
                )
            }
        }
        val staleSyncJob = async(Dispatchers.Default) { repo.syncBlacklist() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            authRepository.setActiveAccount(accountB)
            withTimeout(3.seconds) {
                repo.blockedUsernames.first { it == setOf("SpammerB") }
            }
            release.countDown()
            val staleSyncResult = withTimeout(5.seconds) { staleSyncJob.await() }
            assertTrue("Stale cross-account sync must not succeed, got $staleSyncResult", staleSyncResult is UserError.AuthRequired)
            assertEquals(setOf("SpammerB"), repo.blockedUsernames.value)
            assertEquals(setOf("SpammerB"), store.read(accountB.uid).usernames)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun corruptedBlacklistCacheFile_recoversCleanlyAndAcceptsSubsequentSync() = runBlocking {
        val corruptedFile = File(tempFolder.newFolder(), "blacklist_cache.preferences_pb")
        corruptedFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x11, 0x22, 0x7F))

        val store = BlacklistStore(createDataStore(corruptedFile))
        val (repo, _) = createRepository(store = store)

        assertEquals(emptySet<String>(), repo.blockedUsernames.value)
        assertNull(repo.lastSyncedAtMs.value)

        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("FreshAfterCorruption")),
            ),
        )
        val result = repo.syncBlacklist()
        assertTrue("Expected Success after corruption recovery, got $result", result is BlacklistSyncResult.Success)
        assertEquals(setOf("FreshAfterCorruption"), repo.blockedUsernames.value)
    }

    @Test
    fun addToBlacklist_handles302RedirectAndSilentReloginReplayWithGbkEncoding() = runBlocking {
        val (repo, _) = createRepository()

        // 1. POST addblack -> 302 redirect to pm.php?action=viewblack -> 200 logged-in page
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "pm.php?action=viewblack")
                .build(),
        )
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("RedirectBlocked")),
            ),
        )
        val redirectResult = repo.addToBlacklist(username = "RedirectBlocked", formhash = "fh_302")
        assertTrue("Expected Success after 302 redirect, got $redirectResult", redirectResult is BlacklistMutationResult.Success)
        assertTrue(repo.isBlocked("RedirectBlocked"))
        assertEquals("POST", server.takeRequest().method)
        val redirectedGet = server.takeRequest()
        assertEquals("GET", redirectedGet.method)
        assertEquals("/forum/pm.php?action=viewblack", redirectedGet.target)

        // 2. Session expired on POST addblack -> single silent relogin -> replays GBK POST cleanly
        val reloginCount = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCount.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        server.enqueue(userTestGbkHtmlResponse(userTestLoggedOutHtml("请先登录")))
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "重登后添加成功")))

        val replayResult = repo.addToBlacklist(username = "中文重试", formhash = "fh_replay")
        assertTrue("Expected Success after silent relogin replay, got $replayResult", replayResult is BlacklistMutationResult.Success)
        assertEquals(1, reloginCount.get())
        assertTrue(repo.isBlocked("中文重试"))

        val firstPost = server.takeRequest()
        val replayedPost = server.takeRequest()
        assertEquals("POST", firstPost.method)
        assertEquals("POST", replayedPost.method)
        assertEquals("中文重试", decodeGbkForm(replayedPost.body?.utf8().orEmpty())["user"])
        assertEquals("fh_replay", decodeGbkForm(replayedPost.body?.utf8().orEmpty())["formhash"])
    }

    @Test
    fun userModule_hasExpectedHiltBindingsAndBlacklistDataStorePath() {
        assertTrue(UserModule::class.java.isAnnotationPresent(Module::class.java))

        val bindUserRepo = UserModule::class.java.getDeclaredMethod("bindUserRepository", DefaultUserRepository::class.java)
        assertTrue(bindUserRepo.isAnnotationPresent(Binds::class.java))
        assertTrue(bindUserRepo.isAnnotationPresent(Singleton::class.java))
        assertEquals(UserRepository::class.java, bindUserRepo.returnType)

        val bindBlacklistRepo = UserModule::class.java.getDeclaredMethod(
            "bindBlacklistRepository",
            DefaultBlacklistRepository::class.java,
        )
        assertTrue(bindBlacklistRepo.isAnnotationPresent(Binds::class.java))
        assertTrue(bindBlacklistRepo.isAnnotationPresent(Singleton::class.java))
        assertEquals(BlacklistRepository::class.java, bindBlacklistRepo.returnType)

        val provideDataStore = UserModule.Companion::class.java.getDeclaredMethod(
            "provideBlacklistDataStore",
            Context::class.java,
        )
        assertTrue(provideDataStore.isAnnotationPresent(Provides::class.java))
        assertTrue(provideDataStore.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideDataStore.isAnnotationPresent(BlacklistDataStore::class.java))
        assertEquals(DataStore::class.java, provideDataStore.returnType)

        val context: Context = ApplicationProvider.getApplicationContext()
        val storeFile = UserModule.blacklistDataStoreFile(context)
        assertEquals("blacklist_cache.preferences_pb", storeFile.name)
    }

    @Test
    fun rD8bS1_accountADiskWriteCompletingDuringAccountBInitialReadDoesNotDropAccountBSnapshot() = runBlocking {
        val rawDataStore = createDataStore()
        // Pre-populate both Account A and Account B persisted caches on disk.
        // Using the same snapshot & clock for Account A keeps allBuckets.distinctUntilChanged() quiet
        // on Account A's observer so subscription #1/#2 after switching to Account B are deterministically
        // Account B's observe("10002") and read("10002").
        val seedStore = BlacklistStore(rawDataStore)
        seedStore.replace(accountA.uid, listOf("InitialA"), syncedAtMs = nowMs.get())
        seedStore.replace(accountB.uid, listOf("SpammerB"), syncedAtMs = 200L)

        val pauseNextUpdate = AtomicBoolean(false)
        val accountAWritePaused = CountDownLatch(1)
        val releaseAccountAWrite = CountDownLatch(1)

        val pauseOnSecondDataRead = AtomicBoolean(false)
        val dataReadCounter = AtomicInteger(0)
        val accountBReadPaused = CountDownLatch(1)
        val releaseAccountBRead = CountDownLatch(1)

        val hookedDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                val subscriptionIndex = if (pauseOnSecondDataRead.get()) {
                    dataReadCounter.incrementAndGet()
                } else {
                    0
                }
                rawDataStore.data.collect { prefs ->
                    if (subscriptionIndex == 2) {
                        accountBReadPaused.countDown()
                        releaseAccountBRead.await(5, TimeUnit.SECONDS)
                    }
                    emit(prefs)
                }
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences {
                val updated = rawDataStore.updateData(transform)
                if (pauseNextUpdate.compareAndSet(true, false)) {
                    accountAWritePaused.countDown()
                    releaseAccountAWrite.await(5, TimeUnit.SECONDS)
                }
                return updated
            }
        }

        val (repo, _) = createRepository(store = BlacklistStore(hookedDataStore))
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it == setOf("InitialA") }
        }

        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("InitialA")),
            ),
        )
        pauseNextUpdate.set(true)
        val syncJobA = async(Dispatchers.Default) { repo.syncBlacklist() }

        try {
            assertTrue("Account A disk write did not reach pause point", accountAWritePaused.await(5, TimeUnit.SECONDS))

            // Switch to Account B while Account A is paused right after replace() and before stateLock update
            dataReadCounter.set(0)
            pauseOnSecondDataRead.set(true)
            authRepository.setActiveAccount(accountB)
            assertTrue("Account B initial read did not reach pause point", accountBReadPaused.await(5, TimeUnit.SECONDS))

            // Let Account A finish its post-write block while Account B's read(uid) is still suspended
            releaseAccountAWrite.countDown()
            val syncResA = withTimeout(5.seconds) { syncJobA.await() }
            assertTrue("Expected Account A sync to complete cleanly, got $syncResA", syncResA is BlacklistSyncResult.Success)

            // Release Account B's read(uid) -> MUST publish Account B's persisted ["SpammerB"] rather than dropping it
            releaseAccountBRead.countDown()
            withTimeout(2.seconds) {
                repo.blockedUsernames.first { it == setOf("SpammerB") }
            }
            assertTrue(repo.isBlocked("SpammerB"))
            assertFalse(repo.isBlocked("InitialA"))
        } finally {
            releaseAccountAWrite.countDown()
            releaseAccountBRead.countDown()
        }
    }

    @Test
    fun rD8bS2_syncExposesFormhashCachesPerAccountAndFallsBackWhenInvalidated() = runBlocking {
        val (repo, _) = createRepository()

        // 1. syncBlacklist exposes formhash in Success and caches it for accountA
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    blockedUsers = listOf("Blocked1"),
                    formhashInput = "fh_cached_a1",
                ),
            ),
        )
        val syncRes = repo.syncBlacklist()
        assertTrue("Expected Success, got $syncRes", syncRes is BlacklistSyncResult.Success)
        val syncSuccess = syncRes as BlacklistSyncResult.Success
        assertEquals("fh_cached_a1", syncSuccess.formhash)
        assertEquals("/forum/pm.php?action=viewblack", server.takeRequest().target)

        // 2. removeFromBlacklist + undo addToBlacklist with null formhash reuse cached formhash with ZERO extra GETs
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "移除成功")))
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "撤销重加成功")))

        val removeRes = repo.removeFromBlacklist(username = "Blocked1", formhash = null)
        assertTrue("Expected Success, got $removeRes", removeRes is BlacklistMutationResult.Success)
        val undoRes = repo.addToBlacklist(username = "Blocked1", formhash = null)
        assertTrue("Expected Success, got $undoRes", undoRes is BlacklistMutationResult.Success)

        val delReq = server.takeRequest()
        assertEquals("POST", delReq.method)
        assertEquals("/forum/pm.php?action=delblack", delReq.target)
        assertEquals("fh_cached_a1", decodeGbkForm(delReq.body?.utf8().orEmpty())["formhash"])

        val reAddReq = server.takeRequest()
        assertEquals("POST", reAddReq.method)
        assertEquals("/forum/pm.php?action=addblack", reAddReq.target)
        assertEquals("fh_cached_a1", decodeGbkForm(reAddReq.body?.utf8().orEmpty())["formhash"])

        // 3. Cached formhash rejected by server -> clears cache, fetches fresh viewblack formhash, retries POST once
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = accountA.uid,
                    username = accountA.username,
                    body = """<div class="alert_error">您的请求来路不正确或表单验证串不符，无法提交<a href="index.php">返回</a></div>""",
                ),
            ),
        )
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("Blocked1"), formhashInput = "fh_refreshed_a2"),
            ),
        )
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "重取后成功")))

        val recoveredRemove = repo.removeFromBlacklist(username = "Blocked1", formhash = null)
        assertTrue("Expected Success after formhash refresh fallback, got $recoveredRemove", recoveredRemove is BlacklistMutationResult.Success)
        assertFalse(repo.isBlocked("Blocked1"))

        val stalePost = server.takeRequest()
        assertEquals("POST", stalePost.method)
        assertEquals("fh_cached_a1", decodeGbkForm(stalePost.body?.utf8().orEmpty())["formhash"])
        val refreshGet = server.takeRequest()
        assertEquals("GET", refreshGet.method)
        assertEquals("/forum/pm.php?action=viewblack", refreshGet.target)
        val retriedPost = server.takeRequest()
        assertEquals("POST", retriedPost.method)
        assertEquals("fh_refreshed_a2", decodeGbkForm(retriedPost.body?.utf8().orEmpty())["formhash"])

        // 4. Account switch MUST NOT reuse Account A's cached formhash for Account B
        authRepository.setActiveAccount(accountB)
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it.isEmpty() }
        }
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountB.uid, accountB.username, emptyList(), formhashInput = "fh_account_b"),
            ),
        )
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountB.uid, accountB.username, "添加成功")))
        val addB = repo.addToBlacklist(username = "TargetB", formhash = null)
        assertTrue("Expected Success for Account B, got $addB", addB is BlacklistMutationResult.Success)
        assertEquals("GET", server.takeRequest().method)
        val postB = server.takeRequest()
        assertEquals("POST", postB.method)
        assertEquals("fh_account_b", decodeGbkForm(postB.body?.utf8().orEmpty())["formhash"])

        // 5. Logout then re-login clears cached formhash so next mutation fetches fresh token
        authRepository.logout()
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it.isEmpty() }
        }
        authRepository.bumpEpoch()
        authRepository.setActiveAccount(accountB)
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountB.uid, accountB.username, listOf("TargetB"), formhashInput = "fh_after_relogin"),
            ),
        )
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountB.uid, accountB.username, "移除成功")))
        val removeAfterLogout = repo.removeFromBlacklist(username = "TargetB", formhash = null)
        assertTrue(removeAfterLogout is BlacklistMutationResult.Success)
        assertEquals("GET", server.takeRequest().method)
        val postAfterLogout = server.takeRequest()
        assertEquals("POST", postAfterLogout.method)
        assertEquals("fh_after_relogin", decodeGbkForm(postAfterLogout.body?.utf8().orEmpty())["formhash"])
    }

    @Test
    fun rD8bS3_1_provideBlacklistDataStoreRecoversFromCorruptedFileOnDisk() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storeFile = UserModule.blacklistDataStoreFile(context)
        storeFile.parentFile?.mkdirs()
        storeFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x11, 0x22, 0x7F, 0x55))

        // Directly call production UserModule.provideBlacklistDataStore(context)
        val productionDataStore = UserModule.provideBlacklistDataStore(context)
        // Reading directly (without BlacklistStore's .catch) fails if ReplaceFileCorruptionHandler is removed
        assertEquals(emptyPreferences(), productionDataStore.data.first())

        val store = BlacklistStore(productionDataStore)
        val (repo, _) = createRepository(store = store)
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountA.uid, accountA.username, listOf("RecoveredProd")),
            ),
        )
        val syncResult = repo.syncBlacklist()
        assertTrue("Expected Success after production DataStore corruption recovery, got $syncResult", syncResult is BlacklistSyncResult.Success)
        assertEquals(setOf("RecoveredProd"), store.read(accountA.uid).usernames)
    }

    @Test
    fun rD8bS3_2_verifySessionOwnershipRejectsServerUmenuMismatchEvenWhenLocalAccountUnchanged() = runBlocking {
        val (repo, store) = createRepository()
        store.replace(accountA.uid, listOf("KeepAccountA"), syncedAtMs = 1_000L)
        withTimeout(3.seconds) {
            repo.blockedUsernames.first { it == setOf("KeepAccountA") }
        }

        // Local active account remains accountA (10001), but server #umenu belongs to accountB (10002)
        server.enqueue(
            userTestGbkHtmlResponse(
                viewBlackHtml(accountB.uid, accountB.username, listOf("BobOnlySpammer")),
            ),
        )
        val syncMismatch = repo.syncBlacklist()
        assertTrue("Expected AuthRequired on server #umenu UID mismatch, got $syncMismatch", syncMismatch is UserError.AuthRequired)
        assertEquals(AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED, (syncMismatch as UserError.AuthRequired).reason)
        assertEquals(setOf("KeepAccountA"), repo.blockedUsernames.value)
        assertEquals(setOf("KeepAccountA"), store.read(accountA.uid).usernames)
        assertEquals(emptySet<String>(), store.read(accountB.uid).usernames)

        // Same guard on mutation (addToBlacklist)
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountB.uid, accountB.username, "ok")))
        val addMismatch = repo.addToBlacklist(username = "ShouldNotPersist", formhash = "fh_a")
        assertTrue("Expected AuthRequired on mutation #umenu UID mismatch, got $addMismatch", addMismatch is UserError.AuthRequired)
        assertEquals(setOf("KeepAccountA"), repo.blockedUsernames.value)
        assertFalse(repo.isBlocked("ShouldNotPersist"))
    }

    @Test
    fun rD8bS3_3_allSuspendMethodsForwardBackgroundSilentPriorityWithoutInvokingChallengeSolver() = runBlocking {
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

        val syncChallenge = repo.syncBlacklist(priority = AccessPriority.BackgroundSilent)
        assertTrue("Expected ChallengeRequired, got $syncChallenge", syncChallenge is UserError.ChallengeRequired)
        assertTrue((syncChallenge as UserError.ChallengeRequired).abandoned)

        val addWithHashChallenge = repo.addToBlacklist(
            username = "User1",
            formhash = "fh1",
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue("Expected ChallengeRequired, got $addWithHashChallenge", addWithHashChallenge is UserError.ChallengeRequired)
        assertTrue((addWithHashChallenge as UserError.ChallengeRequired).abandoned)

        val removeWithHashChallenge = repo.removeFromBlacklist(
            username = "User1",
            formhash = "fh1",
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue("Expected ChallengeRequired, got $removeWithHashChallenge", removeWithHashChallenge is UserError.ChallengeRequired)
        assertTrue((removeWithHashChallenge as UserError.ChallengeRequired).abandoned)

        val addPrefetchChallenge = repo.addToBlacklist(
            username = "User1",
            formhash = null,
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue("Expected ChallengeRequired on prefetch, got $addPrefetchChallenge", addPrefetchChallenge is UserError.ChallengeRequired)
        assertTrue((addPrefetchChallenge as UserError.ChallengeRequired).abandoned)

        assertEquals(0, solverCalls.get())
    }

    @Test
    fun rD8bS3_4_viewBlackWithoutInputFormhashFallsBackToUmenuLogoutFormhash() = runBlocking {
        val viewBlackWithoutInputHash = userTestLoggedInHtml(
            uid = accountA.uid,
            username = accountA.username,
            logoutFormhash = "fh_umenu_logout_only",
            body = """
                <div class="blacklist">
                  <p class="nodata">暂无数据</p>
                </div>
            """.trimIndent(),
        )

        // 1. syncBlacklist exposes fallback logoutFormhash
        val (repoForSync, _) = createRepository()
        server.enqueue(userTestGbkHtmlResponse(viewBlackWithoutInputHash))
        val syncResult = repoForSync.syncBlacklist()
        assertTrue("Expected Success, got $syncResult", syncResult is BlacklistSyncResult.Success)
        assertEquals("fh_umenu_logout_only", (syncResult as BlacklistSyncResult.Success).formhash)
        server.takeRequest()

        // 2. fetchFormhash on cold cache falls back to #umenu logoutFormhash
        val (repoForPrefetch, _) = createRepository()
        server.enqueue(userTestGbkHtmlResponse(viewBlackWithoutInputHash))
        server.enqueue(userTestGbkHtmlResponse(actionSuccessHtml(accountA.uid, accountA.username, "添加成功")))
        val addResult = repoForPrefetch.addToBlacklist(username = "FallbackHashTarget", formhash = null)
        assertTrue("Expected Success using logoutFormhash fallback, got $addResult", addResult is BlacklistMutationResult.Success)
        assertEquals("GET", server.takeRequest().method)
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("fh_umenu_logout_only", decodeGbkForm(postReq.body?.utf8().orEmpty())["formhash"])
    }

    private fun viewBlackHtml(
        uid: String,
        username: String,
        blockedUsers: List<String>,
        formhashInput: String = "fh_viewblack_1",
    ): String {
        val listItems = blockedUsers.joinToString("\n") { blocked ->
            """<li><a href="space.php?username=$blocked">$blocked</a></li>"""
        }
        return userTestLoggedInHtml(
            uid = uid,
            username = username,
            body = """
                <form method="post" action="pm.php?action=addblack">
                  <input type="hidden" name="formhash" value="$formhashInput" />
                  <input type="text" name="user" value="" />
                </form>
                <div class="blacklist">
                  <ul class="commonlist">
                    $listItems
                  </ul>
                </div>
            """.trimIndent(),
        )
    }

    private fun emptyConfirmedViewBlackHtml(
        uid: String,
        username: String,
        formhashInput: String = "fh_viewblack_empty",
    ): String = userTestLoggedInHtml(
        uid = uid,
        username = username,
        body = """
            <form method="post" action="pm.php?action=addblack">
              <input type="hidden" name="formhash" value="$formhashInput" />
            </form>
            <div class="blacklist">
              <p class="nodata">暂无数据</p>
            </div>
        """.trimIndent(),
    )

    private fun actionSuccessHtml(
        uid: String,
        username: String,
        infoText: String,
    ): String = userTestLoggedInHtml(
        uid = uid,
        username = username,
        body = """<div class="alert_info"><p>$infoText</p></div>""",
    )

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
