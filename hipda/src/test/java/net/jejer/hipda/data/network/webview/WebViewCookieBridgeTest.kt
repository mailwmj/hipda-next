package net.jejer.hipda.data.network.webview

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import dagger.Provides
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class WebViewCookieBridgeTest {

    private val forumUrl: HttpUrl = "https://www.4d4y.com/forum/".toHttpUrl()
    private val httpDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    private lateinit var store: InMemorySiteCookieStore
    private lateinit var fakeWebView: FakeWebViewCookieAccess
    private lateinit var testDispatcher: ExecutorCoroutineDispatcher
    private lateinit var bridge: WebViewCookieBridge

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        store = InMemorySiteCookieStore()
        fakeWebView = FakeWebViewCookieAccess()
        testDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-webview-cookie-thread").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        bridge = WebViewCookieBridge(
            siteCookieStore = store,
            webViewCookieAccess = fakeWebView,
            cookieDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        testDispatcher.close()
    }

    private fun offsetHttpDate(offsetSeconds: Long): String {
        val epochSeconds = (System.currentTimeMillis() / 1_000L) + offsetSeconds
        return httpDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }

    private fun futureHttpDate(offsetSeconds: Long): String = offsetHttpDate(offsetSeconds)

    private fun futureEpochMillis(offsetSeconds: Long): Long =
        ((System.currentTimeMillis() / 1_000L) + offsetSeconds) * 1_000L

    private fun parseCookie(raw: String, url: HttpUrl = forumUrl): Cookie =
        requireNotNull(Cookie.parse(url, raw)) { "Failed to parse test cookie: $raw" }

    // --- c. snapshotClearances ---

    @Test
    fun snapshotClearances_mergesAndDeduplicatesStoreAndWebViewViaGetCookieInfo() = runBlocking {
        val expires = futureHttpDate(1800)
        store.saveFromResponse(
            forumUrl,
            listOf(
                parseCookie("cf_clearance=shared_token; Domain=.4d4y.com; Path=/; Expires=$expires; Secure"),
                parseCookie("cf_clearance=store_only; Path=/; Expires=$expires; Secure"),
                parseCookie("cdb_auth=auth_val; Path=/"),
            ),
        )
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=shared_token; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=wv_partitioned; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None; Partitioned",
        )
        fakeWebView.seedCookie(forumUrl, "cdb_sid=sid_val; Path=/")
        fakeWebView.rawInjectedCookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=   ; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
                "cf_clearance=evil_token; Domain=.evil.com; Path=/; Expires=$expires; Secure",
            ),
        )

        val snapshot = bridge.snapshotClearances(forumUrl)

        assertEquals(setOf("shared_token", "store_only", "wv_partitioned"), snapshot)
        assertEquals(listOf(forumUrl.toString()), fakeWebView.queriedUrls)
        assertEquals(0, fakeWebView.flushCount)
    }

    @Test
    fun snapshotClearances_mergesAndDeduplicatesViaLegacyGetCookieFallback() = runBlocking {
        val expires = futureHttpDate(1800)
        store.saveFromResponse(
            forumUrl,
            listOf(parseCookie("cf_clearance=store_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure")),
        )
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.rawLegacyHeaderOverride =
            "cdb_sid=123; cf_clearance=legacy_tok; cf_clearance=store_tok; cf_clearance= "

        val snapshot = bridge.snapshotClearances(forumUrl)

        assertEquals(setOf("store_tok", "legacy_tok"), snapshot)
        assertEquals(listOf(forumUrl.toString()), fakeWebView.queriedUrls)
        assertEquals(0, fakeWebView.flushCount)
    }

    // --- d. clearClearance + restoreClearance ---

    @Test
    fun clearClearanceAndRestoreClearance_handlesBothSidesWithDomainSecurePartitionedAndPreservesLoginCookies() =
        runBlocking {
            val expires = futureHttpDate(1800)
            val storeClearance = parseCookie(
                "cf_clearance=store_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
            )
            val storeAuth = parseCookie("cdb_auth=keep_auth; Domain=.4d4y.com; Path=/; HttpOnly")
            val storeSid = parseCookie("cdb_sid=keep_sid; Path=/")
            store.saveFromResponse(forumUrl, listOf(storeClearance, storeAuth, storeSid))

            val wvUnpartitioned =
                "cf_clearance=wv_plain; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None"
            val wvPartitioned =
                "cf_clearance=wv_chips; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None; Partitioned"
            val wvLogin = "cdb_auth=wv_auth; Domain=.4d4y.com; Path=/; HttpOnly"
            fakeWebView.getCookieInfoSupported = true
            fakeWebView.seedCookie(forumUrl, wvUnpartitioned)
            fakeWebView.seedCookie(forumUrl, wvPartitioned)
            fakeWebView.seedCookie(forumUrl, wvLogin)

            val backup = bridge.clearClearance(forumUrl)

            assertFalse(backup.isEmpty)
            assertEquals(listOf(storeClearance), backup.storeCookies)
            assertEquals(listOf(wvUnpartitioned, wvPartitioned), backup.webViewSetCookieHeaders)

            // Store cleared cf_clearance only; cdb_auth and cdb_sid remain intact.
            val remainingStoreNames = store.loadForRequest(forumUrl).map { it.name }.toSet()
            assertEquals(setOf("cdb_auth", "cdb_sid"), remainingStoreNames)

            // WebView received matching expiration Set-Cookie headers for both variants + flush.
            assertEquals(2, fakeWebView.setCookieCalls.size)
            assertTrue(fakeWebView.setCookieCalls.all { it.first == forumUrl.toString() })
            val expireHeaders = fakeWebView.setCookieCalls.map { it.second }
            assertTrue(
                expireHeaders.any { h ->
                    h.startsWith("cf_clearance=;") &&
                        h.contains("Max-Age=0") &&
                        h.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT") &&
                        h.contains("Domain=.4d4y.com") &&
                        h.contains("Path=/") &&
                        h.contains("Secure") &&
                        h.contains("HttpOnly") &&
                        h.contains("SameSite=None") &&
                        !h.contains("Partitioned")
                },
            )
            assertTrue(
                expireHeaders.any { h ->
                    h.startsWith("cf_clearance=;") &&
                        h.contains("Max-Age=0") &&
                        h.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT") &&
                        h.contains("Domain=.4d4y.com") &&
                        h.contains("Path=/") &&
                        h.contains("Secure") &&
                        h.contains("HttpOnly") &&
                        h.contains("SameSite=None") &&
                        h.contains("Partitioned")
                },
            )
            assertEquals(1, fakeWebView.flushCount)
            assertEquals(listOf(wvLogin), fakeWebView.storedRawHeaders(forumUrl))

            // Now simulate challenge failure -> restore backup on both sides.
            fakeWebView.setCookieCalls.clear()
            bridge.restoreClearance(forumUrl, backup)

            val restoredStoreClearance = store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }
            assertEquals(listOf(storeClearance), restoredStoreClearance)
            assertEquals(listOf(wvUnpartitioned, wvPartitioned), fakeWebView.setCookieCalls.map { it.second })
            assertEquals(2, fakeWebView.flushCount)
            assertTrue(fakeWebView.storedRawHeaders(forumUrl).contains(wvUnpartitioned))
            assertTrue(fakeWebView.storedRawHeaders(forumUrl).contains(wvPartitioned))
        }

    @Test
    fun clearClearance_legacyFallbackClearsDomainHostAndHostOnlyClearancesSoFreshChallengeSucceeds() = runBlocking {
        val expires = futureHttpDate(1800)
        fakeWebView.getCookieInfoSupported = false

        // Simulate WebView < 110 storing Cloudflare's Domain=.4d4y.com cookie plus Domain=www.4d4y.com & host-only variants.
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=old_domain_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=old_exact_domain_tok; Domain=www.4d4y.com; Path=/; Expires=$expires; Secure; SameSite=None",
        )
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=old_host_only_tok; Path=/; Expires=$expires; Secure; SameSite=None",
        )
        fakeWebView.seedCookie(forumUrl, "cdb_auth=keep_auth; Domain=.4d4y.com; Path=/; HttpOnly")
        store.saveFromResponse(
            forumUrl,
            listOf(
                parseCookie("cf_clearance=old_domain_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure"),
                parseCookie("cdb_auth=keep_auth; Domain=.4d4y.com; Path=/; HttpOnly"),
            ),
        )

        val snapshot = bridge.snapshotClearances(forumUrl)
        assertEquals(setOf("old_domain_tok", "old_exact_domain_tok", "old_host_only_tok"), snapshot)

        val backup = bridge.clearClearance(forumUrl)

        assertFalse(backup.isEmpty)
        assertEquals(
            setOf(
                "cf_clearance=old_domain_tok; Path=/",
                "cf_clearance=old_exact_domain_tok; Path=/",
                "cf_clearance=old_host_only_tok; Path=/",
            ),
            backup.webViewSetCookieHeaders.toSet(),
        )

        // All three cf_clearance variants must be completely removed from WebView (not left behind on .4d4y.com).
        assertEquals("cdb_auth=keep_auth", fakeWebView.getCookie(forumUrl.toString()))
        assertEquals(listOf("cdb_auth"), store.loadForRequest(forumUrl).map { it.name })

        // Verify the 3 expiration headers cover host-only, Domain=<host>, and Domain=.<topPrivateDomain>.
        assertEquals(3, fakeWebView.setCookieCalls.size)
        val expireHeaders = fakeWebView.setCookieCalls.map { it.second }
        assertEquals(
            listOf(
                "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None",
                "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=www.4d4y.com; Path=/; Secure; SameSite=None",
                "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.4d4y.com; Path=/; Secure; SameSite=None",
            ),
            expireHeaders,
        )
        assertEquals(1, fakeWebView.flushCount)

        // After WebView solves the challenge and writes a new .4d4y.com cf_clearance, sync adopts it cleanly.
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=fresh_domain_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        val syncResult = bridge.syncFromWebView(forumUrl, excludeClearanceValues = snapshot)
        assertEquals("fresh_domain_tok", syncResult.freshClearance)
        assertEquals(
            listOf("fresh_domain_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun clearClearanceAndRestoreClearance_legacyFallbackRestoresAfterClear() = runBlocking {
        val expires = futureHttpDate(1800)
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.seedCookie(forumUrl, "cdb_auth=keep; Path=/")
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=legacy_val; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; SameSite=None",
        )

        val backup = bridge.clearClearance(forumUrl)

        assertFalse(backup.isEmpty)
        assertTrue(backup.storeCookies.isEmpty())
        assertEquals(listOf("cf_clearance=legacy_val; Path=/"), backup.webViewSetCookieHeaders)
        assertEquals("cdb_auth=keep", fakeWebView.getCookie(forumUrl.toString()))
        assertEquals(1, fakeWebView.flushCount)

        fakeWebView.setCookieCalls.clear()
        bridge.restoreClearance(forumUrl, backup)
        assertEquals(listOf("cf_clearance=legacy_val; Path=/"), fakeWebView.setCookieCalls.map { it.second })
        assertEquals(2, fakeWebView.flushCount)
        assertEquals(setOf("legacy_val"), bridge.snapshotClearances(forumUrl))
    }

    @Test
    fun clearClearanceAndRestoreClearance_noOpWhenEmpty() = runBlocking {
        store.saveFromResponse(forumUrl, listOf(parseCookie("cdb_sid=only_sid; Path=/")))
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(forumUrl, "cdb_sid=only_sid; Path=/")

        val backup = bridge.clearClearance(forumUrl)

        assertTrue(backup.isEmpty)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)

        bridge.restoreClearance(forumUrl, backup)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)
    }

    @Test
    fun clearClearance_rollsBackStoreBackupWhenWebViewClearThrows() = runBlocking {
        val expires = futureHttpDate(1800)
        val storeClearance = parseCookie(
            "cf_clearance=store_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        val storeAuth = parseCookie("cdb_auth=keep_auth; Path=/")
        store.saveFromResponse(forumUrl, listOf(storeClearance, storeAuth))

        fakeWebView.getCookieInfoSupported = true
        fakeWebView.onAccessHook = { event ->
            if (event == "getCookieInfo") {
                throw IllegalStateException("Simulated WebView CookieManager IPC failure")
            }
        }

        try {
            bridge.clearClearance(forumUrl)
            fail("Expected IllegalStateException to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("Simulated WebView CookieManager IPC failure", expected.message)
        }

        // Store backup must be rolled back so cf_clearance is not lost.
        assertEquals(
            listOf(storeClearance),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME },
        )
        assertEquals(
            listOf(storeAuth),
            store.loadForRequest(forumUrl).filter { it.name == "cdb_auth" },
        )
    }

    @Test
    fun clearClearance_rollsBackStoreBackupWhenCancelledDuringWebViewClear() = runBlocking {
        val expires = futureHttpDate(1800)
        val storeClearance = parseCookie(
            "cf_clearance=store_tok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )
        store.saveFromResponse(forumUrl, listOf(storeClearance))

        val enteredWebView = CountDownLatch(1)
        val releaseWebView = CountDownLatch(1)
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.onAccessHook = { event ->
            if (event == "getCookieInfo") {
                enteredWebView.countDown()
                releaseWebView.await(5, TimeUnit.SECONDS)
            }
        }

        var caughtCancellation = false
        val job = launch(Dispatchers.Default) {
            try {
                bridge.clearClearance(forumUrl)
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        assertTrue("Bridge should enter cookieDispatcher", enteredWebView.await(5, TimeUnit.SECONDS))
        job.cancel()
        releaseWebView.countDown()
        job.join()

        assertTrue("Expected CancellationException", caughtCancellation)
        assertEquals(
            listOf(storeClearance),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME },
        )
    }

    // --- e. primeWebView ---

    @Test
    fun primeWebView_writesLoginCookiesWithFullAttributesAndStrictlyExcludesCfClearance() = runBlocking {
        val expiresAt = futureEpochMillis(3600)
        val domainAuth = Cookie.Builder()
            .name("cdb_auth")
            .value("auth_payload")
            .domain("4d4y.com")
            .path("/forum")
            .expiresAt(expiresAt)
            .secure()
            .httpOnly()
            .sameSite("Lax")
            .build()
        val hostOnlySid = Cookie.Builder()
            .name("cdb_sid")
            .value("sid_session")
            .hostOnlyDomain("www.4d4y.com")
            .path("/")
            .build()
        val clearance = Cookie.Builder()
            .name(CF_CLEARANCE_COOKIE_NAME)
            .value("must_never_be_primed")
            .domain("4d4y.com")
            .path("/")
            .expiresAt(expiresAt)
            .secure()
            .httpOnly()
            .sameSite("None")
            .build()

        store.saveFromResponse(forumUrl, listOf(domainAuth, hostOnlySid, clearance))

        bridge.primeWebView(forumUrl)

        assertEquals(2, fakeWebView.setCookieCalls.size)
        assertTrue(fakeWebView.setCookieCalls.all { it.first == forumUrl.toString() })
        assertEquals(1, fakeWebView.flushCount)
        assertTrue(fakeWebView.setCookieCalls.none { it.second.startsWith("$CF_CLEARANCE_COOKIE_NAME=") })

        val writtenByName = fakeWebView.setCookieCalls.associate { (_, header) ->
            header.substringBefore('=') to header
        }

        val authHeader = requireNotNull(writtenByName["cdb_auth"])
        val reparsedAuth = parseCookie(authHeader)
        assertEquals("auth_payload", reparsedAuth.value)
        assertEquals("4d4y.com", reparsedAuth.domain)
        assertFalse(reparsedAuth.hostOnly)
        assertEquals("/forum", reparsedAuth.path)
        assertTrue(reparsedAuth.persistent)
        assertEquals(expiresAt, reparsedAuth.expiresAt)
        assertTrue(reparsedAuth.secure)
        assertTrue(reparsedAuth.httpOnly)
        assertEquals("Lax", reparsedAuth.sameSite)

        val sidHeader = requireNotNull(writtenByName["cdb_sid"])
        assertFalse("Host-only cookie must not emit Domain attribute", sidHeader.contains("Domain=", ignoreCase = true))
        assertFalse("Session cookie must not emit Expires attribute", sidHeader.contains("Expires=", ignoreCase = true))
        val reparsedSid = parseCookie(sidHeader)
        assertEquals("sid_session", reparsedSid.value)
        assertEquals("www.4d4y.com", reparsedSid.domain)
        assertTrue(reparsedSid.hostOnly)
        assertEquals("/", reparsedSid.path)
        assertFalse(reparsedSid.persistent)
    }

    @Test
    fun primeWebView_skipsSetCookieAndFlushWhenStoreEmptyOrOnlyContainsCfClearance() = runBlocking {
        bridge.primeWebView(forumUrl)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)

        val expires = futureHttpDate(1800)
        store.saveFromResponse(
            forumUrl,
            listOf(parseCookie("cf_clearance=only_clearance; Domain=.4d4y.com; Path=/; Expires=$expires; Secure")),
        )

        bridge.primeWebView(forumUrl)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)
    }

    // --- f. syncFromWebView (GET_COOKIE_INFO path) ---

    @Test
    fun syncFromWebView_getCookieInfoPreservesAttributesFiltersOldAndPicksLatestClearance() = runBlocking {
        val olderDate = futureHttpDate(900)
        val newerDate = futureHttpDate(3600)
        val loginDate = futureHttpDate(7200)
        val newerExpectedMillis = parseCookie("cf_clearance=x; Expires=$newerDate; Path=/").expiresAt

        fakeWebView.getCookieInfoSupported = true
        fakeWebView.rawInjectedCookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=stale_old_value; Domain=.4d4y.com; Path=/; Expires=$loginDate; Secure; HttpOnly; SameSite=None",
                "cf_clearance=fresh_earlier; Domain=.4d4y.com; Path=/; Expires=$olderDate; Secure; HttpOnly; SameSite=None",
                "cf_clearance=fresh_latest_chips; Domain=.4d4y.com; Path=/; Expires=$newerDate; Secure; HttpOnly; SameSite=None; Partitioned",
                "cf_clearance=   ; Domain=.4d4y.com; Path=/; Expires=$loginDate; Secure",
                "cdb_auth=synced_auth; Domain=.4d4y.com; Path=/forum; Expires=$loginDate; Secure; HttpOnly; SameSite=Lax",
                "cdb_sid=synced_sid; Path=/",
            ),
        )

        val result = bridge.syncFromWebView(
            url = forumUrl,
            excludeClearanceValues = setOf("stale_old_value"),
        )

        assertEquals("fresh_latest_chips", result.freshClearance)
        assertEquals(3, result.syncedCookies.size)

        val stored = store.loadForRequest(forumUrl).associateBy { it.name }
        val clearance = requireNotNull(stored[CF_CLEARANCE_COOKIE_NAME])
        assertEquals("fresh_latest_chips", clearance.value)
        assertEquals("4d4y.com", clearance.domain)
        assertFalse(clearance.hostOnly)
        assertEquals("/", clearance.path)
        assertEquals(newerExpectedMillis, clearance.expiresAt)
        assertTrue(clearance.secure)
        assertTrue(clearance.httpOnly)
        assertEquals("None", clearance.sameSite)

        val auth = requireNotNull(stored["cdb_auth"])
        assertEquals("synced_auth", auth.value)
        assertEquals("4d4y.com", auth.domain)
        assertEquals("/forum", auth.path)
        assertTrue(auth.secure)
        assertTrue(auth.httpOnly)
        assertEquals("Lax", auth.sameSite)

        val sid = requireNotNull(stored["cdb_sid"])
        assertEquals("synced_sid", sid.value)
        assertTrue(sid.hostOnly)
        assertEquals("/", sid.path)
    }

    @Test
    fun syncFromWebView_getCookieInfoPicksLatestClearanceWhenNewerAppearsBeforeOlder() = runBlocking {
        val newerDate = futureHttpDate(3600)
        val olderDate = futureHttpDate(900)
        val newerExpectedMillis = parseCookie("cf_clearance=x; Expires=$newerDate; Path=/").expiresAt

        fakeWebView.getCookieInfoSupported = true
        // Reverse order [newer, older] ensures winner selection compares expiresAt rather than taking last item.
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=fresh_newer_first; Domain=.4d4y.com; Path=/; Expires=$newerDate; Secure; HttpOnly; SameSite=None; Partitioned",
        )
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=fresh_older_second; Domain=.4d4y.com; Path=/; Expires=$olderDate; Secure; HttpOnly; SameSite=None",
        )

        val result = bridge.syncFromWebView(forumUrl)

        assertEquals("fresh_newer_first", result.freshClearance)
        val storedClearance = store.loadForRequest(forumUrl).single { it.name == CF_CLEARANCE_COOKIE_NAME }
        assertEquals("fresh_newer_first", storedClearance.value)
        assertEquals(newerExpectedMillis, storedClearance.expiresAt)
    }

    @Test
    fun syncFromWebViewAndSnapshotAndClear_ignoreAlreadyExpiredCookiesFromGetCookieInfo() = runBlocking {
        val pastDate = offsetHttpDate(-3600)
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.rawInjectedCookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=expired_epoch; Domain=.4d4y.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure",
                "cf_clearance=expired_past; Domain=.4d4y.com; Path=/; Expires=$pastDate; Secure; HttpOnly; SameSite=None",
                "cdb_auth=expired_auth; Domain=.4d4y.com; Path=/; Expires=$pastDate; HttpOnly",
                "cdb_sid=live_sid; Path=/",
            ),
        )

        assertTrue(bridge.snapshotClearances(forumUrl).isEmpty())

        val backup = bridge.clearClearance(forumUrl)
        assertTrue(backup.isEmpty)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)

        val syncResult = bridge.syncFromWebView(forumUrl)
        assertNull(syncResult.freshClearance)
        assertEquals(listOf("cdb_sid"), syncResult.syncedCookies.map { it.name })
        assertEquals(listOf("cdb_sid"), store.loadForRequest(forumUrl).map { it.name })
    }

    @Test
    fun syncFromWebView_clearsOtherDomainAndPathVariantsOfOldCfClearanceInStoreWhenAdoptingFresh() = runBlocking {
        val expires = futureHttpDate(1800)
        val authCookie = parseCookie("cdb_auth=keep_auth; Domain=.4d4y.com; Path=/; HttpOnly")
        store.saveFromResponse(
            forumUrl,
            listOf(
                parseCookie("cf_clearance=old_host_only; Path=/; Expires=$expires; Secure"),
                parseCookie("cf_clearance=old_subpath; Domain=.4d4y.com; Path=/forum; Expires=$expires; Secure"),
                authCookie,
            ),
        )

        // GET_COOKIE_INFO path adopts fresh Domain=.4d4y.com Path=/ cf_clearance -> old host-only & /forum variants must be deleted.
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=fresh_domain; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
        )

        val result1 = bridge.syncFromWebView(forumUrl, excludeClearanceValues = setOf("old_host_only", "old_subpath"))
        assertEquals("fresh_domain", result1.freshClearance)
        assertEquals(listOf("fresh_domain"), result1.syncedCookies.map { it.value })
        val afterFirstSync = store.loadForRequest(forumUrl)
        assertEquals(
            listOf("fresh_domain"),
            afterFirstSync.filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertEquals(listOf(authCookie), afterFirstSync.filter { it.name == "cdb_auth" })

        // Legacy getCookie path adopts fresh host-only cf_clearance -> old Domain=.4d4y.com variant in store must be deleted.
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.rawLegacyHeaderOverride = "cf_clearance=fresh_legacy_host; cdb_sid=new_sid"

        val result2 = bridge.syncFromWebView(forumUrl, excludeClearanceValues = setOf("fresh_domain"))
        assertEquals("fresh_legacy_host", result2.freshClearance)
        assertEquals(setOf("cf_clearance", "cdb_sid"), result2.syncedCookies.map { it.name }.toSet())
        val afterSecondSync = store.loadForRequest(forumUrl)
        val remainingClearances = afterSecondSync.filter { it.name == CF_CLEARANCE_COOKIE_NAME }
        assertEquals(1, remainingClearances.size)
        assertEquals("fresh_legacy_host", remainingClearances.single().value)
        assertTrue(remainingClearances.single().hostOnly)
        assertEquals(listOf(authCookie), afterSecondSync.filter { it.name == "cdb_auth" })
    }

    @Test
    fun syncFromWebView_returnsNullFreshClearanceWhenOnlyOldClearancePresentButStillSyncsLoginCookies() = runBlocking {
        val expires = futureHttpDate(1800)
        val existingStoreClearance = parseCookie(
            "cf_clearance=existing_store_ok; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
        )
        store.saveFromResponse(forumUrl, listOf(existingStoreClearance))

        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=old_only; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
        )
        fakeWebView.seedCookie(forumUrl, "cdb_auth=fresh_login; Path=/")

        val result = bridge.syncFromWebView(
            url = forumUrl,
            excludeClearanceValues = setOf("old_only"),
        )

        assertNull(result.freshClearance)
        assertEquals(listOf("cdb_auth"), result.syncedCookies.map { it.name })
        // Because no fresh cf_clearance was adopted, existing store cf_clearance stays untouched.
        assertEquals(setOf("cf_clearance", "cdb_auth"), store.loadForRequest(forumUrl).map { it.name }.toSet())
    }

    // --- g. syncFromWebView (getCookie fallback path) ---

    @Test
    fun syncFromWebView_legacyGetCookieFallbackParsesEqualsInValuesAndFiltersClearance() = runBlocking {
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.rawLegacyHeaderOverride =
            "cf_clearance=old_val; cf_clearance=new_val=with==eq; cdb_auth=base64==payload; =skip_empty_name; malformed; cdb_sid=sid_99"

        val result = bridge.syncFromWebView(
            url = forumUrl,
            excludeClearanceValues = setOf("old_val"),
        )

        assertEquals("new_val=with==eq", result.freshClearance)
        assertEquals(setOf("cf_clearance", "cdb_auth", "cdb_sid"), result.syncedCookies.map { it.name }.toSet())

        val stored = store.loadForRequest(forumUrl).associateBy { it.name }
        val clearance = requireNotNull(stored[CF_CLEARANCE_COOKIE_NAME])
        assertEquals("new_val=with==eq", clearance.value)
        assertEquals("www.4d4y.com", clearance.domain)
        assertTrue(clearance.hostOnly)
        assertEquals("/", clearance.path)

        val auth = requireNotNull(stored["cdb_auth"])
        assertEquals("base64==payload", auth.value)
        assertEquals("www.4d4y.com", auth.domain)
        assertTrue(auth.hostOnly)
        assertEquals("/", auth.path)

        val sid = requireNotNull(stored["cdb_sid"])
        assertEquals("sid_99", sid.value)
        assertTrue(sid.hostOnly)
        assertEquals("/", sid.path)
    }

    // --- h. Host validation & URL isolation ---

    @Test
    fun syncFromWebView_rejectsCookiesForMismatchedOrUnrelatedHosts() = runBlocking {
        val expires = futureHttpDate(1800)
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.rawInjectedCookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=evil_clearance; Domain=.evil.com; Path=/; Expires=$expires; Secure",
                "cdb_auth=other_subdomain; Domain=other.4d4y.com; Path=/; Expires=$expires",
                "stolen=suffix_trick; Domain=www.4d4y.com.evil.com; Path=/; Expires=$expires",
                "public_suffix=bad; Domain=.com; Path=/; Expires=$expires",
                "cdb_sid=valid_parent; Domain=.4d4y.com; Path=/; Expires=$expires",
                "valid_host=ok; Path=/",
            ),
        )

        val result = bridge.syncFromWebView(forumUrl)

        assertNull(result.freshClearance)
        assertEquals(setOf("cdb_sid", "valid_host"), result.syncedCookies.map { it.name }.toSet())
        assertEquals(setOf("cdb_sid", "valid_host"), store.snapshot().map { it.name }.toSet())
    }

    @Test
    fun bridgeOperationsPassExactTargetUrlAndIsolateDomainsAndPaths() = runBlocking {
        val expires = futureHttpDate(1800)
        val adminUrl = "https://www.4d4y.com/admin/".toHttpUrl()
        val otherHostUrl = "https://other.4d4y.com/forum/".toHttpUrl()

        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(forumUrl, "cf_clearance=forum_cf; Domain=.4d4y.com; Path=/forum; Expires=$expires; Secure")
        fakeWebView.seedCookie(adminUrl, "cf_clearance=admin_cf; Domain=.4d4y.com; Path=/admin; Expires=$expires; Secure")
        fakeWebView.seedCookie(otherHostUrl, "cf_clearance=other_cf; Path=/; Expires=$expires; Secure")

        assertEquals(setOf("forum_cf"), bridge.snapshotClearances(forumUrl))
        assertEquals(setOf("admin_cf"), bridge.snapshotClearances(adminUrl))
        assertEquals(listOf(forumUrl.toString(), adminUrl.toString()), fakeWebView.queriedUrls)

        val syncResult = bridge.syncFromWebView(forumUrl)
        assertEquals("forum_cf", syncResult.freshClearance)
        assertEquals(listOf("forum_cf"), syncResult.syncedCookies.map { it.value })
    }

    // --- i. Dedicated single-thread dispatcher, flush ordering, and module wiring ---

    @Test
    fun allWebViewCookieAccessCallsRunOnDedicatedCookieDispatcherAndWritesFlush() = runBlocking {
        val callerThread = Thread.currentThread()
        val dedicatedThread = withContext(testDispatcher) { Thread.currentThread() }
        val expires = futureHttpDate(1800)
        store.saveFromResponse(
            forumUrl,
            listOf(
                parseCookie("cdb_auth=auth1; Path=/"),
                parseCookie("cf_clearance=old_cf; Domain=.4d4y.com; Path=/; Expires=$expires; Secure"),
            ),
        )
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.seedCookie(
            forumUrl,
            "cf_clearance=old_cf; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
        )

        bridge.snapshotClearances(forumUrl)
        val backup = bridge.clearClearance(forumUrl)
        bridge.restoreClearance(forumUrl, backup)
        bridge.primeWebView(forumUrl)
        bridge.syncFromWebView(forumUrl)

        assertTrue("Expected WebViewCookieAccess calls to be recorded", fakeWebView.recordedThreads.isNotEmpty())
        assertTrue(
            "Every WebViewCookieAccess call must run on the dedicated cookie dispatcher thread, got: ${fakeWebView.recordedThreads}",
            fakeWebView.recordedThreads.all { (thread, name) ->
                thread === dedicatedThread &&
                    (name == "test-webview-cookie-thread" || name.startsWith("test-webview-cookie-thread "))
            },
        )
        assertNotEquals(dedicatedThread, callerThread)

        // Verify exact event ordering: each write burst ends with flush()
        val writeEvents = fakeWebView.eventLog.filter { it == "setCookie" || it == "flush" }
        assertEquals(
            listOf(
                "setCookie", "flush", // clearClearance
                "setCookie", "flush", // restoreClearance
                "setCookie", "flush", // primeWebView
            ),
            writeEvents,
        )
    }

    @Test
    fun webViewNetworkModuleProvidesNamedDaemonSingleThreadDispatcherAndHiltBindings() = runBlocking {
        val bindMethod = WebViewNetworkModule::class.java.getDeclaredMethod(
            "bindWebViewCookieAccess",
            AndroidWebViewCookieAccess::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))

        val provideMethod = WebViewNetworkModule.Companion::class.java.getDeclaredMethod(
            "provideWebViewCookieDispatcher",
        )
        assertTrue(provideMethod.isAnnotationPresent(Provides::class.java))
        assertTrue(provideMethod.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideMethod.isAnnotationPresent(WebViewCookieDispatcher::class.java))

        val dispatcher = WebViewNetworkModule.provideWebViewCookieDispatcher()
        try {
            val (threadName, isDaemon) = withContext(dispatcher) {
                Thread.currentThread().name to Thread.currentThread().isDaemon
            }
            assertTrue(
                "Expected thread name starting with webview-cookie-bridge, got: $threadName",
                threadName == "webview-cookie-bridge" || threadName.startsWith("webview-cookie-bridge "),
            )
            assertTrue("Cookie bridge thread should be a daemon thread", isDaemon)
        } finally {
            (dispatcher as? ExecutorCoroutineDispatcher)?.close()
        }
    }

    @Test
    fun androidWebViewCookieAccessDoesNotHoldCookieManagerFieldInConstructor() {
        val access = AndroidWebViewCookieAccess()
        assertTrue(
            "AndroidWebViewCookieAccess should not store CookieManager as a field initialized at construction time",
            access::class.java.declaredFields.none { it.type.name == "android.webkit.CookieManager" },
        )
    }

    /**
     * Realistic in-memory [WebViewCookieAccess] backed by a cookie store keyed by
     * `(name, domain, path, hostOnly, partitioned)` to match Chromium `CookieManager` behavior:
     * - A host-only expiration header (`hostOnly = true`) does NOT delete a domain-scoped cookie
     *   (`Domain=.4d4y.com` or `Domain=www.4d4y.com`, `hostOnly = false`), and vice versa.
     * - Both `getCookieInfo(url)` and `getCookie(url)` parse [url] and filter via [Cookie.matches].
     */
    private class FakeWebViewCookieAccess : WebViewCookieAccess {
        private data class CookieScopeKey(
            val name: String,
            val domain: String,
            val path: String,
            val hostOnly: Boolean,
            val partitioned: Boolean,
        )

        private data class StoredEntry(
            val cookie: Cookie,
            val rawHeader: String,
        )

        var getCookieInfoSupported: Boolean = true
        var onAccessHook: ((String) -> Unit)? = null
        val rawInjectedCookieInfoHeaders: MutableList<String> = mutableListOf()
        var rawLegacyHeaderOverride: String? = null

        private val entries = LinkedHashMap<CookieScopeKey, StoredEntry>()

        val setCookieCalls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
        val queriedUrls: MutableList<String> = CopyOnWriteArrayList()
        val recordedThreads: MutableList<Pair<Thread, String>> = CopyOnWriteArrayList()
        val eventLog: MutableList<String> = CopyOnWriteArrayList()
        var flushCount: Int = 0
            private set

        fun seedCookie(url: HttpUrl, setCookieHeader: String) {
            applySetCookie(url, setCookieHeader)
        }

        fun storedRawHeaders(url: HttpUrl): List<String> =
            matchingEntries(url).map { it.rawHeader }

        private fun record(event: String) {
            val current = Thread.currentThread()
            recordedThreads.add(current to current.name)
            eventLog.add(event)
            onAccessHook?.invoke(event)
        }

        override fun isGetCookieInfoSupported(): Boolean {
            record("isGetCookieInfoSupported")
            return getCookieInfoSupported
        }

        override fun getCookieInfo(url: String): List<String> {
            record("getCookieInfo")
            val parsedUrl = url.toHttpUrl()
            queriedUrls.add(url)
            return matchingEntries(parsedUrl).map { it.rawHeader } + rawInjectedCookieInfoHeaders
        }

        override fun getCookie(url: String): String? {
            record("getCookie")
            val parsedUrl = url.toHttpUrl()
            queriedUrls.add(url)
            rawLegacyHeaderOverride?.let { return it }
            val matched = matchingEntries(parsedUrl)
            if (matched.isEmpty()) return null
            return matched.joinToString("; ") { "${it.cookie.name}=${it.cookie.value}" }
        }

        override fun setCookie(url: String, value: String) {
            record("setCookie")
            val parsedUrl = url.toHttpUrl()
            setCookieCalls.add(url to value)
            applySetCookie(parsedUrl, value)
        }

        override fun flush() {
            record("flush")
            flushCount++
        }

        private fun applySetCookie(url: HttpUrl, rawHeader: String) {
            val cookie = Cookie.parse(url, rawHeader) ?: return
            if (cookie.sameSite.equals("None", ignoreCase = true) && !cookie.secure) return
            if (cookie.secure && !url.isHttps) return
            val partitioned = hasAttributeFlag(rawHeader, "partitioned")
            val key = CookieScopeKey(
                name = cookie.name,
                domain = cookie.domain,
                path = cookie.path,
                hostOnly = cookie.hostOnly,
                partitioned = partitioned,
            )
            val now = System.currentTimeMillis()
            if (cookie.expiresAt <= now) {
                entries.remove(key)
            } else {
                entries[key] = StoredEntry(cookie = cookie, rawHeader = rawHeader)
            }
        }

        private fun matchingEntries(url: HttpUrl): List<StoredEntry> {
            val now = System.currentTimeMillis()
            entries.entries.removeAll { it.value.cookie.expiresAt <= now }
            return entries.values.filter { it.cookie.matches(url) }
        }

        private fun hasAttributeFlag(rawHeader: String, flagName: String): Boolean =
            rawHeader.split(';')
                .drop(1)
                .any { it.trim().substringBefore('=').trim().equals(flagName, ignoreCase = true) }
    }
}
