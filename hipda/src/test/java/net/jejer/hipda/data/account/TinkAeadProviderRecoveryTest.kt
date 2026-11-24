package net.jejer.hipda.data.account

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TinkAeadProviderRecoveryTest {

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
    }

    @Test
    fun getOrCreateAead_secondFailurePureIoException_throwsWithoutDeletingMasterKeyOrEvictingCache() {
        val backend = ScriptedKeysetBackend(
            failuresByKeyset = mapOf(
                "hx_credentials_keyset" to ArrayDeque(
                    listOf(
                        GeneralSecurityException("Corrupted keyset entry on first load"),
                        IOException("Disk full while writing new keyset"),
                    ),
                ),
            ),
        )
        val provider = TinkAeadProvider(backend)

        // Pre-populate another cached keyset to verify Stage 2 cache wipe does not run on IOException.
        val healthyOtherAead = provider.getOrCreateAead("other_keyset")

        val thrown = assertThrows(IOException::class.java) {
            provider.getOrCreateAead("credentials_keyset")
        }
        assertEquals("Disk full while writing new keyset", thrown.message)
        assertEquals(1, thrown.suppressed.size)
        assertEquals(GeneralSecurityException::class.java, thrown.suppressed[0].javaClass)

        assertEquals(2, backend.attemptsFor("hx_credentials_keyset"))
        assertEquals(listOf("hx_credentials_keyset"), backend.clearedKeysets)
        assertEquals(0, backend.masterKeyDeletes.get())

        // Existing cached AEAD for another keyset remains intact.
        assertSame(healthyOtherAead, provider.getOrCreateAead("other_keyset"))
        assertEquals(1, backend.attemptsFor("hx_other_keyset"))
    }

    @Test
    fun getOrCreateAead_secondFailureIoExceptionAfterFirstIoException_doesNotDeleteMasterKey() {
        val backend = ScriptedKeysetBackend(
            failuresByKeyset = mapOf(
                "hx_credentials_keyset" to ArrayDeque(
                    listOf(
                        IOException("Truncated keyset read"),
                        IOException("Storage I/O still failing"),
                    ),
                ),
            ),
        )
        val provider = TinkAeadProvider(backend)

        assertThrows(IOException::class.java) {
            provider.getOrCreateAead("credentials_keyset")
        }

        assertEquals(2, backend.attemptsFor("hx_credentials_keyset"))
        assertEquals(listOf("hx_credentials_keyset"), backend.clearedKeysets)
        assertEquals(0, backend.masterKeyDeletes.get())
    }

    @Test
    fun getOrCreateAead_secondFailureProviderException_deletesMasterKeyAndRecoversOnThird() {
        val backend = ScriptedKeysetBackend(
            failuresByKeyset = mapOf(
                "hx_credentials_keyset" to ArrayDeque(
                    listOf(
                        GeneralSecurityException("Initial decrypt failed"),
                        ProviderException("AndroidKeyStore binder failure"),
                    ),
                ),
            ),
        )
        val provider = TinkAeadProvider(backend)

        val aead = provider.getOrCreateAead("credentials_keyset")
        val aad = provider.associatedDataFor("credentials")
        val ciphertext = aead.encrypt("ok".toByteArray(), aad)

        assertArrayEquals("ok".toByteArray(), aead.decrypt(ciphertext, aad))
        assertEquals(3, backend.attemptsFor("hx_credentials_keyset"))
        assertEquals(
            listOf("hx_credentials_keyset", "hx_credentials_keyset"),
            backend.clearedKeysets,
        )
        assertEquals(1, backend.masterKeyDeletes.get())
    }

    private class ScriptedKeysetBackend(
        private val failuresByKeyset: Map<String, ArrayDeque<Throwable>>,
    ) : KeysetBackend {
        private val attemptsByKeyset = mutableMapOf<String, AtomicInteger>()
        val clearedKeysets = CopyOnWriteArrayList<String>()
        val masterKeyDeletes = AtomicInteger(0)

        fun attemptsFor(normalizedKeysetName: String): Int =
            attemptsByKeyset[normalizedKeysetName]?.get() ?: 0

        override fun buildAead(normalizedKeysetName: String): Aead {
            attemptsByKeyset.getOrPut(normalizedKeysetName) { AtomicInteger(0) }.incrementAndGet()
            val nextFailure = failuresByKeyset[normalizedKeysetName]?.removeFirstOrNull()
            if (nextFailure != null) {
                throw nextFailure
            }
            return KeysetHandle.generateNew(
                PredefinedAeadParameters.AES256_GCM,
                RegistryConfiguration.get(),
            ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        override fun clearKeysetEntry(normalizedKeysetName: String) {
            clearedKeysets.add(normalizedKeysetName)
        }

        override fun deleteMasterKeyAlias() {
            masterKeyDeletes.incrementAndGet()
        }
    }
}
