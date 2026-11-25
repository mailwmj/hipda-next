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
import java.util.concurrent.Executors
import javax.inject.Singleton
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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

    private fun futureHttpDate(offsetSeconds: Long): String {
        val epochSeconds = (System.currentTimeMillis() / 1_000L) + offsetSeconds
        return httpDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }

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
        fakeWebView.cookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=shared_token; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None",
                "cf_clearance=wv_partitioned; Domain=.4d4y.com; Path=/; Expires=$expires; Secure; HttpOnly; SameSite=None; Partitioned",
                "cf_clearance=   ; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
                "cf_clearance=evil_token; Domain=.evil.com; Path=/; Expires=$expires; Secure",
                "cdb_sid=sid_val; Path=/",
            ),
        )

        val snapshot = bridge.snapshotClearances(forumUrl)

        assertEquals(setOf("shared_token", "store_only", "wv_partitioned"), snapshot)
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
        fakeWebView.legacyCookieHeader = "cdb_sid=123; cf_clearance=legacy_tok; cf_clearance=store_tok; cf_clearance= "

        val snapshot = bridge.snapshotClearances(forumUrl)

        assertEquals(setOf("store_tok", "legacy_tok"), snapshot)
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
            fakeWebView.cookieInfoHeaders.addAll(listOf(wvUnpartitioned, wvPartitioned, wvLogin))

            val backup = bridge.clearClearance(forumUrl)

            assertFalse(backup.isEmpty)
            assertEquals(listOf(storeClearance), backup.storeCookies)
            assertEquals(listOf(wvUnpartitioned, wvPartitioned), backup.webViewSetCookieHeaders)

            // Store cleared cf_clearance only; cdb_auth and cdb_sid remain intact.
            val remainingStoreNames = store.loadForRequest(forumUrl).map { it.name }.toSet()
            assertEquals(setOf("cdb_auth", "cdb_sid"), remainingStoreNames)

            // WebView received matching expiration Set-Cookie headers for both variants + flush.
            assertEquals(2, fakeWebView.setCookieCalls.size)
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
            assertEquals(listOf(wvLogin), fakeWebView.cookieInfoHeaders)

            // Now simulate challenge failure -> restore backup on both sides.
            fakeWebView.setCookieCalls.clear()
            bridge.restoreClearance(forumUrl, backup)

            val restoredStoreClearance = store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }
            assertEquals(listOf(storeClearance), restoredStoreClearance)
            assertEquals(listOf(wvUnpartitioned, wvPartitioned), fakeWebView.setCookieCalls.map { it.second })
            assertEquals(2, fakeWebView.flushCount)
            assertTrue(fakeWebView.cookieInfoHeaders.contains(wvUnpartitioned))
            assertTrue(fakeWebView.cookieInfoHeaders.contains(wvPartitioned))
        }

    @Test
    fun clearClearanceAndRestoreClearance_legacyFallbackWritesHostOnlyPathRoot() = runBlocking {
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.legacyCookieHeader = "cdb_auth=keep; cf_clearance=legacy_val"

        val backup = bridge.clearClearance(forumUrl)

        assertFalse(backup.isEmpty)
        assertTrue(backup.storeCookies.isEmpty())
        assertEquals(listOf("cf_clearance=legacy_val; Path=/"), backup.webViewSetCookieHeaders)
        assertEquals(1, fakeWebView.setCookieCalls.size)
        val expireHeader = fakeWebView.setCookieCalls.single().second
        assertEquals("cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/", expireHeader)
        assertEquals(1, fakeWebView.flushCount)

        fakeWebView.setCookieCalls.clear()
        bridge.restoreClearance(forumUrl, backup)
        assertEquals(listOf("cf_clearance=legacy_val; Path=/"), fakeWebView.setCookieCalls.map { it.second })
        assertEquals(2, fakeWebView.flushCount)
    }

    @Test
    fun clearClearanceAndRestoreClearance_noOpWhenEmpty() = runBlocking {
        store.saveFromResponse(forumUrl, listOf(parseCookie("cdb_sid=only_sid; Path=/")))
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.cookieInfoHeaders.add("cdb_sid=only_sid; Path=/")

        val backup = bridge.clearClearance(forumUrl)

        assertTrue(backup.isEmpty)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)

        bridge.restoreClearance(forumUrl, backup)
        assertTrue(fakeWebView.setCookieCalls.isEmpty())
        assertEquals(0, fakeWebView.flushCount)
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
        fakeWebView.cookieInfoHeaders.addAll(
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
    fun syncFromWebView_returnsNullFreshClearanceWhenOnlyOldClearancePresentButStillSyncsLoginCookies() = runBlocking {
        val expires = futureHttpDate(1800)
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.cookieInfoHeaders.addAll(
            listOf(
                "cf_clearance=old_only; Domain=.4d4y.com; Path=/; Expires=$expires; Secure",
                "cdb_auth=fresh_login; Path=/",
            ),
        )

        val result = bridge.syncFromWebView(
            url = forumUrl,
            excludeClearanceValues = setOf("old_only"),
        )

        assertNull(result.freshClearance)
        assertEquals(listOf("cdb_auth"), result.syncedCookies.map { it.name })
        assertEquals(listOf("cdb_auth"), store.loadForRequest(forumUrl).map { it.name })
    }

    // --- g. syncFromWebView (getCookie fallback path) ---

    @Test
    fun syncFromWebView_legacyGetCookieFallbackParsesEqualsInValuesAndFiltersClearance() = runBlocking {
        fakeWebView.getCookieInfoSupported = false
        fakeWebView.legacyCookieHeader =
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

    // --- h. Host validation ---

    @Test
    fun syncFromWebView_rejectsCookiesForMismatchedOrUnrelatedHosts() = runBlocking {
        val expires = futureHttpDate(1800)
        fakeWebView.getCookieInfoSupported = true
        fakeWebView.cookieInfoHeaders.addAll(
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
        fakeWebView.cookieInfoHeaders.add(
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
     * In-memory fake for [WebViewCookieAccess] that simulates both `GET_COOKIE_INFO`
     * and legacy `getCookie` modes, records thread affinity and call sequence, and
     * applies `Max-Age=0` deletions on matching cookie scopes.
     */
    private class FakeWebViewCookieAccess : WebViewCookieAccess {
        var getCookieInfoSupported: Boolean = true
        val cookieInfoHeaders: MutableList<String> = mutableListOf()
        var legacyCookieHeader: String? = null

        val setCookieCalls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
        val recordedThreads: MutableList<Pair<Thread, String>> = CopyOnWriteArrayList()
        val eventLog: MutableList<String> = CopyOnWriteArrayList()
        var flushCount: Int = 0
            private set

        private fun record(event: String) {
            val current = Thread.currentThread()
            recordedThreads.add(current to current.name)
            eventLog.add(event)
        }

        override fun isGetCookieInfoSupported(): Boolean {
            record("isGetCookieInfoSupported")
            return getCookieInfoSupported
        }

        override fun getCookieInfo(url: String): List<String> {
            record("getCookieInfo")
            return cookieInfoHeaders.toList()
        }

        override fun getCookie(url: String): String? {
            record("getCookie")
            return legacyCookieHeader
        }

        override fun setCookie(url: String, value: String) {
            record("setCookie")
            setCookieCalls.add(url to value)
            if (getCookieInfoSupported) {
                val targetKey = headerScopeKey(value)
                val isDeletion = value.contains("Max-Age=0", ignoreCase = true) ||
                    value.contains("1970", ignoreCase = true)
                cookieInfoHeaders.removeAll { headerScopeKey(it) == targetKey }
                if (!isDeletion) {
                    cookieInfoHeaders.add(value)
                }
            } else {
                val name = value.substringBefore('=').trim()
                val rawVal = value.substringAfter('=', "").substringBefore(';').trim()
                val isDeletion = value.contains("Max-Age=0", ignoreCase = true) || rawVal.isEmpty()
                val pairs = legacyCookieHeader
                    ?.split(';')
                    ?.mapNotNull { seg ->
                        val idx = seg.indexOf('=')
                        if (idx <= 0) null else seg.substring(0, idx).trim() to seg.substring(idx + 1).trim()
                    }
                    ?.filterNot { it.first == name }
                    ?.toMutableList()
                    ?: mutableListOf()
                if (!isDeletion) {
                    pairs.add(name to rawVal)
                }
                legacyCookieHeader = pairs.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.first}=${it.second}" }
            }
        }

        override fun flush() {
            record("flush")
            flushCount++
        }

        private fun headerScopeKey(raw: String): String {
            val parts = raw.split(';')
            val name = parts.firstOrNull()?.substringBefore('=')?.trim().orEmpty()
            var domain = ""
            var path = "/"
            var partitioned = false
            for (part in parts.drop(1)) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                val attr = (if (eq >= 0) trimmed.substring(0, eq) else trimmed).trim().lowercase(Locale.ROOT)
                val attrVal = if (eq >= 0) trimmed.substring(eq + 1).trim().lowercase(Locale.ROOT) else ""
                when (attr) {
                    "domain" -> domain = attrVal.trimStart('.')
                    "path" -> path = attrVal
                    "partitioned" -> partitioned = true
                }
            }
            return "$name|$domain|$path|$partitioned"
        }
    }
}
