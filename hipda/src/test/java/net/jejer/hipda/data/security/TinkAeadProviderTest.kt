package net.jejer.hipda.data.security

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TinkAeadProviderTest {

    private lateinit var backend: FakeKeysetBackend
    private lateinit var provider: TinkAeadProvider

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        backend = FakeKeysetBackend()
        provider = TinkAeadProvider(backend)
    }

    @Test
    fun encryptedFileName_enforcesHxPrefixAndValidatesInput() {
        assertEquals("hx_cookies.pb", provider.encryptedFileName("cookies.pb"))
        assertEquals("hx_credentials.pb", provider.encryptedFileName("hx_credentials.pb"))
        assertEquals("hx_trimmed", provider.encryptedFileName("  trimmed  "))
        assertEquals("hx_key-1_v2.pb", provider.encryptedFileName("key-1_v2.pb"))

        val invalidInputs = listOf(
            "",
            "   ",
            "hx_",
            "  hx_  ",
            ".",
            "hx_.",
            "..",
            "../escape",
            "hx_a..b",
            "dir/file",
            "dir\\file",
            "line\nbreak",
            "null\u0000byte",
            "has space",
        )
        for (invalid in invalidInputs) {
            assertThrows("Expected IllegalArgumentException for '$invalid'", IllegalArgumentException::class.java) {
                provider.encryptedFileName(invalid)
            }
        }
    }

    @Test
    fun getOrCreateAead_cachesPerNormalizedKeysetName() {
        val first = provider.getOrCreateAead("cookies_keyset")
        val second = provider.getOrCreateAead("hx_cookies_keyset")

        assertSame(first, second)
        assertEquals(1, backend.buildAttempts.get())
    }

    @Test
    fun encryptAndDecrypt_roundTripsAndRejectsMismatchedAad() {
        val aead = provider.getOrCreateAead()
        val plaintext = "cdb_auth=secret_token; cdb_sid=12345".toByteArray(Charsets.UTF_8)
        val cookieAad = provider.associatedDataFor("cookies")
        val credAad = provider.associatedDataFor("credentials")

        val ciphertext = aead.encrypt(plaintext, cookieAad)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertArrayEquals(plaintext, aead.decrypt(ciphertext, cookieAad))
        assertThrows(GeneralSecurityException::class.java) {
            aead.decrypt(ciphertext, credAad)
        }
    }

    @Test
    fun wrapSerializer_isLazyUntilReadOrWrite() = runTest {
        val serializer = provider.wrapSerializer("cookies.pb", Utf8StringSerializer)

        // Construction and defaultValue access must not trigger Tink/Keystore I/O.
        assertEquals("", serializer.defaultValue)
        assertEquals(0, backend.buildAttempts.get())

        val output = ByteArrayOutputStream()
        serializer.writeTo("lazy-payload", output)
        assertEquals(1, backend.buildAttempts.get())

        val decrypted = serializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals("lazy-payload", decrypted)
        assertEquals(1, backend.buildAttempts.get())
    }

    @Test
    fun wrapSerializer_encryptsDataStorePayloadAndBlocksCiphertextSwap() = runTest {
        val cookieSerializer = provider.wrapSerializer("cookies.pb", Utf8StringSerializer)
        val credentialsSerializer = provider.wrapSerializer("credentials.pb", Utf8StringSerializer)

        val output = ByteArrayOutputStream()
        cookieSerializer.writeTo("secret-cookie-payload", output)
        val encryptedBytes = output.toByteArray()

        assertFalse(String(encryptedBytes, Charsets.UTF_8).contains("secret-cookie-payload"))

        val decrypted = cookieSerializer.readFrom(ByteArrayInputStream(encryptedBytes))
        assertEquals("secret-cookie-payload", decrypted)

        try {
            credentialsSerializer.readFrom(ByteArrayInputStream(encryptedBytes))
            throw AssertionError("Expected CorruptionException when reading with mismatched AAD")
        } catch (_: CorruptionException) {
            // Expected
        }
    }

    @Test
    fun getOrCreateAead_recoversFromCorruptedKeysetEntryWithoutDeletingMasterKey() {
        val recoverableErrors: List<() -> Throwable> = listOf(
            { GeneralSecurityException("AEADBadTagException simulated") },
            { IOException("Truncated keyset proto") },
            { IllegalArgumentException("Invalid hex in keyset pref") },
            { ClassCastException("Corrupted SharedPreferences entry type") },
        )

        for ((index, errorFactory) in recoverableErrors.withIndex()) {
            val faultBackend = FakeKeysetBackend(
                failuresBeforeSuccess = 1,
                failureFactory = errorFactory,
            )
            val faultProvider = TinkAeadProvider(faultBackend)
            val keysetName = "cookies_keyset_$index"

            val aead = faultProvider.getOrCreateAead(keysetName)
            val aad = faultProvider.associatedDataFor("cookies")
            val encrypted = aead.encrypt("ok".toByteArray(), aad)

            assertArrayEquals("ok".toByteArray(), aead.decrypt(encrypted, aad))
            assertEquals(2, faultBackend.buildAttempts.get())
            assertEquals(listOf("hx_$keysetName"), faultBackend.clearedKeysets)
            assertEquals(0, faultBackend.masterKeyDeletes.get())
        }
    }

    @Test
    fun getOrCreateAead_deletesMasterKeyAliasWhenSecondBuildFailsAndRecoversOnThird() {
        val faultBackend = FakeKeysetBackend(
            failuresBeforeSuccess = 2,
            failureFactory = { GeneralSecurityException("KeyPermanentlyInvalidatedException simulated") },
        )
        val faultProvider = TinkAeadProvider(faultBackend)

        val aead = faultProvider.getOrCreateAead("credentials_keyset")
        val aad = faultProvider.associatedDataFor("credentials")
        val ciphertext = aead.encrypt("recovered".toByteArray(), aad)

        assertArrayEquals("recovered".toByteArray(), aead.decrypt(ciphertext, aad))
        assertEquals(3, faultBackend.buildAttempts.get())
        assertEquals(
            listOf("hx_credentials_keyset", "hx_credentials_keyset"),
            faultBackend.clearedKeysets,
        )
        assertEquals(1, faultBackend.masterKeyDeletes.get())
    }

    @Test
    fun getOrCreateAead_failsClosedWhenKeystoreRemainsUnavailable() = runTest {
        val brokenBackend = FakeKeysetBackend(
            failuresBeforeSuccess = Int.MAX_VALUE,
            failureFactory = { KeyStoreException("Hardware Keystore unavailable") },
        )
        val brokenProvider = TinkAeadProvider(brokenBackend)

        // wrapSerializer must stay lazy and not crash on construction.
        val serializer = brokenProvider.wrapSerializer("cookies.pb", Utf8StringSerializer)
        assertEquals(0, brokenBackend.buildAttempts.get())

        // Direct getOrCreateAead attempts targeted clear + master key reset, then fails closed.
        assertThrows(KeyStoreException::class.java) {
            brokenProvider.getOrCreateAead()
        }
        assertEquals(3, brokenBackend.buildAttempts.get())
        assertEquals(2, brokenBackend.clearedKeysets.size)
        assertEquals(1, brokenBackend.masterKeyDeletes.get())

        // Serializer writeTo must fail closed (never write plaintext) and readFrom must not swallow into CorruptionException.
        val out = ByteArrayOutputStream()
        try {
            serializer.writeTo("secret-must-not-leak", out)
            throw AssertionError("Expected KeyStoreException on writeTo when Keystore is unavailable")
        } catch (_: KeyStoreException) {
            // Expected fail-closed
        }
        assertEquals(0, out.size())

        try {
            serializer.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            throw AssertionError("Expected KeyStoreException on readFrom when Keystore is unavailable")
        } catch (_: KeyStoreException) {
            // Expected fail-closed
        }
    }

    @Test
    fun keysetRebuild_turnsOldCiphertextIntoCorruptionExceptionAndDataStoreRecovers() = runTest {
        val tempDir = Files.createTempDirectory("tink-datastore-recovery").toFile()
        val initialFile = File(tempDir, provider.encryptedFileName("cookies_initial.pb"))
        val restartedFile = File(tempDir, provider.encryptedFileName("cookies_restarted.pb"))
        val job1 = SupervisorJob()
        val job2 = SupervisorJob()
        val scope1 = CoroutineScope(job1 + Dispatchers.IO)
        val scope2 = CoroutineScope(job2 + Dispatchers.IO)
        try {
            // 1. Write encrypted payload using initial healthy keyset.
            val initialStore = DataStoreFactory.create(
                serializer = provider.wrapSerializer("cookies.pb", Utf8StringSerializer),
                scope = scope1,
                produceFile = { initialFile },
            )
            initialStore.updateData { "old-cookie-token" }
            assertEquals("old-cookie-token", initialStore.data.first())
            val rawOnDisk = initialFile.readBytes()
            assertFalse(String(rawOnDisk, Charsets.UTF_8).contains("old-cookie-token"))
            job1.cancelAndJoin()
            restartedFile.writeBytes(rawOnDisk)

            // 2. Simulate restart where keyset entry is corrupted -> first build fails,
            //    provider clears keyset entry and rebuilds a fresh keyset on retry.
            val recoveredBackend = FakeKeysetBackend(
                failuresBeforeSuccess = 1,
                failureFactory = { GeneralSecurityException("Keyset decrypt failed after restore") },
            )
            val recoveredProvider = TinkAeadProvider(recoveredBackend)
            val recoveredSerializer = recoveredProvider.wrapSerializer("cookies.pb", Utf8StringSerializer)

            // Lazy construction: no Keystore call yet before I/O.
            assertEquals(0, recoveredBackend.buildAttempts.get())

            // Direct read of old ciphertext throws CorruptionException after keyset self-healing.
            try {
                recoveredSerializer.readFrom(ByteArrayInputStream(rawOnDisk))
                throw AssertionError("Expected CorruptionException for ciphertext encrypted with old keyset")
            } catch (_: CorruptionException) {
                // Expected
            }
            assertEquals(2, recoveredBackend.buildAttempts.get())
            assertEquals(listOf(AeadProvider.DEFAULT_KEYSET_NAME), recoveredBackend.clearedKeysets)

            // 3. End-to-end DataStore with ReplaceFileCorruptionHandler resets cleanly to empty state.
            val handlerInvoked = AtomicBoolean(false)
            val recoveredStore = DataStoreFactory.create(
                serializer = recoveredProvider.wrapSerializer("cookies.pb", Utf8StringSerializer),
                corruptionHandler = ReplaceFileCorruptionHandler {
                    handlerInvoked.set(true)
                    ""
                },
                scope = scope2,
                produceFile = { restartedFile },
            )
            assertEquals("", recoveredStore.data.first())
            assertTrue(handlerInvoked.get())

            // Subsequent writes use the rebuilt keyset and round-trip cleanly.
            recoveredStore.updateData { "fresh-cookie-token" }
            assertEquals("fresh-cookie-token", recoveredStore.data.first())
        } finally {
            scope1.cancel()
            scope2.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun getOrCreateAead_concurrentInitializationAcrossDistinctKeysetsSucceeds() {
        val threadCount = 12
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = CopyOnWriteArrayList<Throwable>()
        try {
            val futures = (0 until threadCount).map { idx ->
                executor.submit {
                    try {
                        barrier.await(10, TimeUnit.SECONDS)
                        TinkAeadProvider.ensureTinkRegistered()
                        val aead = provider.getOrCreateAead("concurrent_keyset_$idx")
                        val aad = provider.associatedDataFor("store_$idx")
                        val payload = "payload-$idx".toByteArray(Charsets.UTF_8)
                        val encrypted = aead.encrypt(payload, aad)
                        assertArrayEquals(payload, aead.decrypt(encrypted, aad))
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertTrue("Unexpected concurrency errors: $errors", errors.isEmpty())
            assertEquals(threadCount, backend.buildAttempts.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun idempotentInitializer_blocksConcurrentCallersUntilDoneAndRetriesAfterFailure() {
        // Part 1: Failure on first attempt must NOT leave initialized stuck at true.
        val attempts = AtomicInteger(0)
        val retryInitializer = IdempotentInitializer {
            if (attempts.incrementAndGet() == 1) {
                throw GeneralSecurityException("Transient registration failure")
            }
        }
        assertThrows(GeneralSecurityException::class.java) {
            retryInitializer.ensureInitialized()
        }
        retryInitializer.ensureInitialized()
        retryInitializer.ensureInitialized()
        assertEquals(2, attempts.get())

        // Part 2: Concurrent callers must wait until the first thread finishes initialization.
        val entered = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val runCount = AtomicInteger(0)
        val blockingInitializer = IdempotentInitializer {
            runCount.incrementAndGet()
            entered.countDown()
            assertTrue(allowFinish.await(5, TimeUnit.SECONDS))
            completed.set(true)
        }

        val workerCount = 6
        val waitersStarted = CountDownLatch(workerCount - 1)
        val executor = Executors.newFixedThreadPool(workerCount)
        val waiterSawIncomplete = AtomicBoolean(false)
        try {
            val leader = executor.submit { blockingInitializer.ensureInitialized() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val waiters = (1 until workerCount).map {
                executor.submit {
                    waitersStarted.countDown()
                    blockingInitializer.ensureInitialized()
                    if (!completed.get()) {
                        waiterSawIncomplete.set(true)
                    }
                }
            }
            assertTrue(waitersStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(30)
            allowFinish.countDown()

            leader.get(10, TimeUnit.SECONDS)
            waiters.forEach { it.get(10, TimeUnit.SECONDS) }

            assertFalse("A concurrent caller returned before initialization completed!", waiterSawIncomplete.get())
            assertEquals(1, runCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeKeysetBackend(
        private val failuresBeforeSuccess: Int = 0,
        private val failureFactory: () -> Throwable = { GeneralSecurityException("Simulated failure") },
    ) : KeysetBackend {
        val buildAttempts = AtomicInteger(0)
        val clearedKeysets = CopyOnWriteArrayList<String>()
        val masterKeyDeletes = AtomicInteger(0)

        override fun buildAead(normalizedKeysetName: String): Aead {
            val attempt = buildAttempts.incrementAndGet()
            if (attempt <= failuresBeforeSuccess) {
                throw failureFactory()
            }
            return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        override fun clearKeysetEntry(normalizedKeysetName: String) {
            clearedKeysets.add(normalizedKeysetName)
        }

        override fun deleteMasterKeyAlias() {
            masterKeyDeletes.incrementAndGet()
        }
    }

    private object Utf8StringSerializer : Serializer<String> {
        override val defaultValue: String = ""

        override suspend fun readFrom(input: InputStream): String =
            input.readBytes().toString(Charsets.UTF_8)

        override suspend fun writeTo(t: String, output: OutputStream) {
            output.write(t.toByteArray(Charsets.UTF_8))
        }
    }
}
