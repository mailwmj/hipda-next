package net.jejer.hipda.data.network.cookie

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.security.AeadProvider
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSiteCookieStoreTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var aeadProvider: AeadProvider
    private lateinit var clockMillis: AtomicLong

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        aeadProvider = TinkAeadProvider(InMemoryKeysetBackend())
        clockMillis = AtomicLong(System.currentTimeMillis())
    }

    private fun newCookieFile(provider: AeadProvider = aeadProvider): File =
        File(tempFolder.newFolder(), provider.encryptedFileName(CookieStoreModule.COOKIE_STORE_LOGICAL_NAME))

    private suspend fun <T> withStore(
        file: File,
        provider: AeadProvider = aeadProvider,
        clock: () -> Long = clockMillis::get,
        block: suspend (DataStoreSiteCookieStore) -> T,
    ): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        return try {
            val dataStore = CookieStoreModule.createCookieDataStore(
                produceFile = { file },
                aeadProvider = provider,
                scope = scope,
            )
            val store = DataStoreSiteCookieStore(
                dataStore = dataStore,
                persistScope = scope,
                clock = clock,
            )
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun hostOnlyCookie_sentOnlyToOriginHostAndNotSubdomains() = runBlocking {
        val file = newCookieFile()
        withStore(file) { store ->
            val apexUrl = "https://4d4y.com/forum/".toHttpUrl()
            val wwwUrl = "https://www.4d4y.com/forum/".toHttpUrl()
            val subWwwUrl = "https://sub.www.4d4y.com/forum/".toHttpUrl()

            val apexHostOnly = requireNotNull(Cookie.parse(apexUrl, "apex_only=1; Path=/"))
            val wwwHostOnly = requireNotNull(Cookie.parse(wwwUrl, "www_only=2; Path=/"))
            assertTrue(apexHostOnly.hostOnly)
            assertTrue(wwwHostOnly.hostOnly)

            store.saveFromResponse(apexUrl, listOf(apexHostOnly))
            store.saveFromResponse(wwwUrl, listOf(wwwHostOnly))

            assertEquals(listOf("apex_only"), store.loadForRequest(apexUrl).map { it.name })
            assertEquals(listOf("www_only"), store.loadForRequest(wwwUrl).map { it.name })
            assertTrue(store.loadForRequest(subWwwUrl).isEmpty())
        }
    }

    @Test
    fun domainCookie_withLeadingDot_sentToApexAndSubdomainsOnly() = runBlocking {
        val file = newCookieFile()
        withStore(file) { store ->
            val wwwUrl = "https://www.4d4y.com/forum/".toHttpUrl()
            val domainCookie = requireNotNull(
                Cookie.parse(wwwUrl, "cdb_auth=token123; Domain=.4d4y.com; Path=/"),
            )
            assertFalse(domainCookie.hostOnly)

            store.saveFromResponse(wwwUrl, listOf(domainCookie))

            assertEquals(
                listOf("cdb_auth"),
                store.loadForRequest("https://www.4d4y.com/forum/index.php".toHttpUrl()).map { it.name },
            )
            assertEquals(
                listOf("cdb_auth"),
                store.loadForRequest("https://4d4y.com/".toHttpUrl()).map { it.name },
            )
            assertEquals(
                listOf("cdb_auth"),
                store.loadForRequest("https://img.4d4y.com/a.jpg".toHttpUrl()).map { it.name },
            )
            assertTrue(store.loadForRequest("https://evil4d4y.com/".toHttpUrl()).isEmpty())
            assertTrue(store.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun pathMatching_andSecureAttribute_followRfc6265() = runBlocking {
        val file = newCookieFile()
        withStore(file) { store ->
            val httpsForum = "https://www.4d4y.com/forum/index.php".toHttpUrl()
            val pathCookie = requireNotNull(
                Cookie.parse(httpsForum, "forum_cookie=yes; Path=/forum"),
            )
            val secureCookie = requireNotNull(
                Cookie.parse(httpsForum, "cf_clearance=secret; Path=/; Secure"),
            )

            store.saveFromResponse(httpsForum, listOf(pathCookie, secureCookie))

            assertEquals(
                setOf("forum_cookie", "cf_clearance"),
                store.loadForRequest("https://www.4d4y.com/forum".toHttpUrl()).map { it.name }.toSet(),
            )
            assertEquals(
                setOf("forum_cookie", "cf_clearance"),
                store.loadForRequest("https://www.4d4y.com/forum/viewthread.php".toHttpUrl()).map { it.name }.toSet(),
            )
            assertEquals(
                listOf("cf_clearance"),
                store.loadForRequest("https://www.4d4y.com/".toHttpUrl()).map { it.name },
            )
            assertEquals(
                listOf("cf_clearance"),
                store.loadForRequest("https://www.4d4y.com/forum2/index.php".toHttpUrl()).map { it.name },
            )

            // Secure cookie must NOT be sent over HTTP; non-secure path cookie still is.
            assertEquals(
                listOf("forum_cookie"),
                store.loadForRequest("http://www.4d4y.com/forum/index.php".toHttpUrl()).map { it.name },
            )
            assertTrue(store.loadForRequest("http://www.4d4y.com/".toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun expirationAndDeletionByExpiry_andSameNameDifferentPathCoexistence() = runBlocking {
        val file = newCookieFile()
        val url = "https://www.4d4y.com/forum/index.php".toHttpUrl()
        withStore(file) { store ->
            val now = clockMillis.get()
            val shortLived = Cookie.Builder()
                .name("cf_clearance")
                .value("v1")
                .domain("4d4y.com")
                .path("/")
                .expiresAt(now + 1_800_000L)
                .build()
            val alreadyExpired = Cookie.Builder()
                .name("stale")
                .value("old")
                .domain("4d4y.com")
                .path("/")
                .expiresAt(now - 1_000L)
                .build()
            val rootSid = Cookie.Builder()
                .name("cdb_sid")
                .value("root_val")
                .hostOnlyDomain("www.4d4y.com")
                .path("/")
                .build()
            val forumSid = Cookie.Builder()
                .name("cdb_sid")
                .value("forum_val")
                .hostOnlyDomain("www.4d4y.com")
                .path("/forum")
                .build()

            store.saveFromResponse(url, listOf(shortLived, alreadyExpired, rootSid, forumSid))

            // Already expired cookie is ignored; same name with different paths coexist (longer path first).
            assertEquals(
                listOf("cdb_sid" to "root_val", "cf_clearance" to "v1"),
                store.loadForRequest("https://www.4d4y.com/".toHttpUrl())
                    .map { it.name to it.value }
                    .sortedBy { it.first },
            )
            val forumCookies = store.loadForRequest(url).filter { it.name == "cdb_sid" }
            assertEquals(listOf("forum_val", "root_val"), forumCookies.map { it.value })

            // Server overwriting with expired timestamp deletes only the matching (name, domain, path).
            val deleteForumSid = Cookie.Builder()
                .name("cdb_sid")
                .value("")
                .hostOnlyDomain("www.4d4y.com")
                .path("/forum")
                .expiresAt(now - 1L)
                .build()
            store.saveFromResponse(url, listOf(deleteForumSid))

            assertEquals(
                listOf("root_val"),
                store.loadForRequest(url).filter { it.name == "cdb_sid" }.map { it.value },
            )

            // Advancing clock past 30 minutes expires cf_clearance.
            clockMillis.addAndGet(1_800_001L)
            assertTrue(store.loadForRequest(url).none { it.name == "cf_clearance" })
            assertTrue(store.snapshot().none { it.name == "cf_clearance" })
            store.flush()
        }

        // Verify deletion persisted across restart.
        withStore(file) { restarted ->
            assertEquals(
                listOf("cdb_sid" to "root_val"),
                restarted.loadForRequest(url).map { it.name to it.value },
            )
        }
    }

    @Test
    fun restartStore_retainsSessionAndPersistentCookiesWithAllAttributes() = runBlocking {
        val file = newCookieFile()
        val url = "https://www.4d4y.com/forum/index.php".toHttpUrl()
        val now = clockMillis.get()

        val sessionSid = requireNotNull(Cookie.parse(url, "cdb_sid=abc123; Path=/; HttpOnly"))
        val sessionAuth = requireNotNull(
            Cookie.parse(url, "cdb_auth=login_token; Domain=.4d4y.com; Path=/; Secure; HttpOnly"),
        )
        val persistentCf = Cookie.Builder()
            .name("cf_clearance")
            .value("cf_val_999")
            .domain("4d4y.com")
            .path("/")
            .expiresAt(now + 1_800_000L)
            .secure()
            .httpOnly()
            .sameSite("Lax")
            .build()

        assertFalse(sessionSid.persistent)
        assertFalse(sessionAuth.persistent)
        assertTrue(persistentCf.persistent)

        withStore(file) { store ->
            store.saveFromResponse(url, listOf(sessionSid, sessionAuth, persistentCf))
            store.flush()
        }

        withStore(file) { restarted ->
            val loaded = restarted.loadForRequest(url).associateBy { it.name }
            assertEquals(3, loaded.size)
            assertEquals(sessionSid, loaded["cdb_sid"])
            assertEquals(sessionAuth, loaded["cdb_auth"])
            assertEquals(persistentCf, loaded["cf_clearance"])
            assertFalse(loaded.getValue("cdb_sid").persistent)
            assertFalse(loaded.getValue("cdb_auth").persistent)
            assertTrue(loaded.getValue("cf_clearance").persistent)
            assertEquals("Lax", loaded.getValue("cf_clearance").sameSite)
        }
    }

    @Test
    fun clear_emptiesStoreInMemoryAndPersistsAcrossRestart() = runBlocking {
        val file = newCookieFile()
        val url = "https://www.4d4y.com/forum/".toHttpUrl()

        withStore(file) { store ->
            store.saveFromResponse(
                url,
                listOf(
                    requireNotNull(Cookie.parse(url, "cdb_auth=secret; Domain=.4d4y.com; Path=/")),
                    requireNotNull(Cookie.parse(url, "cdb_sid=session; Path=/")),
                ),
            )
            store.flush()
            assertEquals(2, store.snapshot().size)

            store.clear()

            assertTrue(store.snapshot().isEmpty())
            assertTrue(store.loadForRequest(url).isEmpty())
        }

        withStore(file) { restarted ->
            assertTrue(restarted.snapshot().isEmpty())
            assertTrue(restarted.loadForRequest(url).isEmpty())
        }
    }

    @Test
    fun clearForHost_removesMatchingHostAndDomainCookiesAndPersists() = runBlocking {
        val file = newCookieFile()
        val wwwUrl = "https://www.4d4y.com/forum/".toHttpUrl()
        val apexUrl = "https://4d4y.com/".toHttpUrl()
        val imgUrl = "https://img.4d4y.com/".toHttpUrl()
        val otherUrl = "https://example.com/".toHttpUrl()

        withStore(file) { store ->
            store.saveFromResponse(
                wwwUrl,
                listOf(
                    requireNotNull(Cookie.parse(wwwUrl, "www_host=1; Path=/")),
                    requireNotNull(Cookie.parse(wwwUrl, "shared_domain=2; Domain=.4d4y.com; Path=/")),
                ),
            )
            store.saveFromResponse(apexUrl, listOf(requireNotNull(Cookie.parse(apexUrl, "apex_host=3; Path=/"))))
            store.saveFromResponse(imgUrl, listOf(requireNotNull(Cookie.parse(imgUrl, "img_host=4; Path=/"))))
            store.saveFromResponse(otherUrl, listOf(requireNotNull(Cookie.parse(otherUrl, "other=5; Path=/"))))
            store.flush()

            store.clearForHost("WWW.4D4Y.COM")

            assertTrue(store.loadForRequest(wwwUrl).isEmpty())
            assertEquals(listOf("apex_host"), store.loadForRequest(apexUrl).map { it.name })
            assertEquals(listOf("img_host"), store.loadForRequest(imgUrl).map { it.name })
            assertEquals(listOf("other"), store.loadForRequest(otherUrl).map { it.name })
        }

        withStore(file) { restarted ->
            assertTrue(restarted.loadForRequest(wwwUrl).isEmpty())
            assertEquals(setOf("apex_host", "img_host", "other"), restarted.snapshot().map { it.name }.toSet())
        }
    }

    @Test
    fun corruptedEncryptedFile_recoversEmptyAndAllowsSubsequentWrites() = runBlocking {
        val file = newCookieFile()
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        val url = "https://www.4d4y.com/forum/".toHttpUrl()

        withStore(file) { store ->
            assertTrue(store.loadForRequest(url).isEmpty())
            assertTrue(store.snapshot().isEmpty())

            val recovered = requireNotNull(Cookie.parse(url, "cdb_sid=after_recovery; Path=/"))
            store.saveFromResponse(url, listOf(recovered))
            store.flush()
        }

        withStore(file) { restarted ->
            assertEquals(listOf("after_recovery"), restarted.loadForRequest(url).map { it.value })
        }
    }

    @Test
    fun generalSecurityExceptionOnDecrypt_recoversEmptyAndAllowsSubsequentWrites() = runBlocking {
        val file = newCookieFile()
        val url = "https://www.4d4y.com/forum/".toHttpUrl()

        // Write valid encrypted cookies with keyset #1.
        withStore(file, provider = aeadProvider) { store ->
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_auth=old_key; Path=/"))))
            store.flush()
        }

        // Simulate a provider whose serializer throws raw GeneralSecurityException on first corrupt read.
        val rawGseProvider = RawDecryptGseAeadProvider(TinkAeadProvider(InMemoryKeysetBackend()))
        withStore(file, provider = rawGseProvider) { store ->
            assertTrue(store.loadForRequest(url).isEmpty())
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_auth=new_key; Path=/"))))
            store.flush()
        }

        withStore(file, provider = rawGseProvider) { restarted ->
            assertEquals(listOf("new_key"), restarted.loadForRequest(url).map { it.value })
        }
    }

    @Test
    fun tinkKeysetMismatchAndTruncatedEmptyFile_recoverEmptyAndAllowSubsequentWrites() = runBlocking {
        val url = "https://www.4d4y.com/forum/".toHttpUrl()

        // 1. Ciphertext encrypted under a different Tink keyset triggers AeadSerializer CorruptionException.
        val mismatchFile = newCookieFile()
        withStore(mismatchFile, provider = aeadProvider) { store ->
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_auth=key1; Path=/"))))
            store.flush()
        }
        val rebuiltProvider = TinkAeadProvider(InMemoryKeysetBackend())
        withStore(mismatchFile, provider = rebuiltProvider) { store ->
            assertTrue(store.loadForRequest(url).isEmpty())
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_auth=key2; Path=/"))))
            store.flush()
        }
        withStore(mismatchFile, provider = rebuiltProvider) { restarted ->
            assertEquals(listOf("key2"), restarted.loadForRequest(url).map { it.value })
        }

        // 2. Existing 0-byte truncated file recovers cleanly.
        val emptyFile = newCookieFile()
        emptyFile.parentFile?.mkdirs()
        emptyFile.writeBytes(ByteArray(0))
        withStore(emptyFile) { store ->
            assertTrue(store.loadForRequest(url).isEmpty())
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_sid=rebuilt; Path=/"))))
            store.flush()
        }
        withStore(emptyFile) { restarted ->
            assertEquals(listOf("rebuilt"), restarted.loadForRequest(url).map { it.value })
        }
    }

    @Test
    fun persistedFileOnDisk_usesHxPrefixAndContainsNoCookiePlaintext() = runBlocking {
        val file = newCookieFile()
        assertTrue(file.name.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX))
        assertEquals("hx_site_cookies", file.name)

        val url = "https://www.4d4y.com/forum/".toHttpUrl()
        val authSecret = "SUPER_SECRET_CDB_AUTH_987654321"
        val sidSecret = "SESSION_SECRET_SID_123456789"
        val cfSecret = "CF_CLEARANCE_SECRET_TOKEN_XYZ"

        withStore(file) { store ->
            store.saveFromResponse(
                url,
                listOf(
                    requireNotNull(Cookie.parse(url, "cdb_auth=$authSecret; Domain=.4d4y.com; Path=/; Secure")),
                    requireNotNull(Cookie.parse(url, "cdb_sid=$sidSecret; Path=/")),
                    requireNotNull(Cookie.parse(url, "cf_clearance=$cfSecret; Domain=.4d4y.com; Path=/")),
                ),
            )
            store.flush()
        }

        assertTrue(file.exists())
        val rawBytes = file.readBytes()
        assertTrue(rawBytes.isNotEmpty())
        val rawUtf8 = rawBytes.toString(Charsets.UTF_8)
        val rawLatin1 = rawBytes.toString(Charsets.ISO_8859_1)
        for (secret in listOf(authSecret, sidSecret, cfSecret, "cdb_auth", "cdb_sid", "4d4y.com")) {
            assertFalse("Plaintext '$secret' leaked in UTF-8 view", rawUtf8.contains(secret))
            assertFalse("Plaintext '$secret' leaked in Latin-1 view", rawLatin1.contains(secret))
        }
    }

    @Test
    fun loadForRequest_usesMemoryCacheWithoutReReadingDisk() = runBlocking {
        val file = newCookieFile()
        val url = "https://www.4d4y.com/forum/".toHttpUrl()
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val delegate = CookieStoreModule.createCookieDataStore(
                produceFile = { file },
                aeadProvider = aeadProvider,
                scope = scope,
            )
            val diskReadEmissions = AtomicInteger(0)
            val countingStore = object : DataStore<StoredCookieStore> {
                override val data: Flow<StoredCookieStore> = delegate.data.onEach {
                    diskReadEmissions.incrementAndGet()
                }

                override suspend fun updateData(
                    transform: suspend (t: StoredCookieStore) -> StoredCookieStore,
                ): StoredCookieStore = delegate.updateData(transform)
            }

            val store = DataStoreSiteCookieStore(countingStore, scope, clockMillis::get)
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_sid=cached; Path=/"))))
            store.flush()

            repeat(50) {
                assertEquals(listOf("cached"), store.loadForRequest(url).map { it.value })
            }
            assertEquals(1, diskReadEmissions.get())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun rapidConcurrentSaves_serializeDiskWritesAndKeepLastModification() = runBlocking {
        val url = "https://www.4d4y.com/forum/".toHttpUrl()
        val activeWrites = AtomicInteger(0)
        val maxConcurrentWrites = AtomicInteger(0)
        val lastPersisted = AtomicReference(StoredCookieStore())

        val fakeDataStore = object : DataStore<StoredCookieStore> {
            override val data: Flow<StoredCookieStore> = flow { emit(lastPersisted.get()) }

            override suspend fun updateData(
                transform: suspend (t: StoredCookieStore) -> StoredCookieStore,
            ): StoredCookieStore {
                val active = activeWrites.incrementAndGet()
                maxConcurrentWrites.accumulateAndGet(active, ::maxOf)
                try {
                    delay(15)
                    val updated = transform(lastPersisted.get())
                    lastPersisted.set(updated)
                    return updated
                } finally {
                    activeWrites.decrementAndGet()
                }
            }
        }

        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val store = DataStoreSiteCookieStore(fakeDataStore, scope, clockMillis::get)
            val count = 30
            coroutineScope {
                for (i in 0 until count) {
                    launch(Dispatchers.Default) {
                        store.saveFromResponse(
                            url,
                            listOf(
                                requireNotNull(Cookie.parse(url, "k_$i=v_$i; Path=/")),
                                requireNotNull(Cookie.parse(url, "latest=v_$i; Path=/")),
                            ),
                        )
                    }
                }
            }
            // Write a deterministic final value after the concurrent burst.
            store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "latest=final_val; Path=/"))))
            store.flush()

            assertEquals(1, maxConcurrentWrites.get())
            val persistedByName = lastPersisted.get().cookies.associateBy { it.name }
            assertEquals("final_val", persistedByName["latest"]?.value)
            for (i in 0 until count) {
                assertNotNull("Missing persisted cookie k_$i", persistedByName["k_$i"])
                assertEquals("v_$i", persistedByName["k_$i"]?.value)
            }
        } finally {
            job.cancelAndJoin()
        }
    }

    private class InMemoryKeysetBackend : KeysetBackend {
        override fun buildAead(normalizedKeysetName: String): Aead =
            KeysetHandle.generateNew(
                PredefinedAeadParameters.AES256_GCM,
                RegistryConfiguration.get(),
            ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        override fun clearKeysetEntry(normalizedKeysetName: String) = Unit
        override fun deleteMasterKeyAlias() = Unit
    }

    private class RawDecryptGseAeadProvider(
        private val delegate: AeadProvider,
    ) : AeadProvider by delegate {
        override fun <T> wrapSerializer(
            logicalName: String,
            wrappedSerializer: Serializer<T>,
            keysetName: String,
        ): Serializer<T> {
            val aead = delegate.getOrCreateAead(keysetName)
            val aad = delegate.associatedDataFor(logicalName)
            return object : Serializer<T> {
                override val defaultValue: T = wrappedSerializer.defaultValue

                override suspend fun readFrom(input: InputStream): T {
                    // Intentionally lets GeneralSecurityException propagate unwrapped to verify recovery.
                    val plaintext = aead.decrypt(input.readBytes(), aad)
                    return wrappedSerializer.readFrom(plaintext.inputStream())
                }

                override suspend fun writeTo(t: T, output: OutputStream) {
                    val buffer = ByteArrayOutputStream()
                    wrappedSerializer.writeTo(t, buffer)
                    output.write(aead.encrypt(buffer.toByteArray(), aad))
                }
            }
        }
    }
}
