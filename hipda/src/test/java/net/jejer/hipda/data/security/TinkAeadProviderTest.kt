package net.jejer.hipda.data.security

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TinkAeadProviderTest {

    private val createdCount = AtomicInteger(0)
    private lateinit var provider: TinkAeadProvider

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        provider = TinkAeadProvider { _ ->
            createdCount.incrementAndGet()
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }

    @Test
    fun encryptedFileName_enforcesHxPrefixAndValidatesInput() {
        assertEquals("hx_cookies.pb", provider.encryptedFileName("cookies.pb"))
        assertEquals("hx_credentials.pb", provider.encryptedFileName("hx_credentials.pb"))
        assertEquals("hx_trimmed", provider.encryptedFileName("  trimmed  "))

        assertThrows(IllegalArgumentException::class.java) {
            provider.encryptedFileName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.encryptedFileName("../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.encryptedFileName("dir/file")
        }
    }

    @Test
    fun getOrCreateAead_cachesPerNormalizedKeysetName() {
        val first = provider.getOrCreateAead("cookies_keyset")
        val second = provider.getOrCreateAead("hx_cookies_keyset")

        assertSame(first, second)
        assertEquals(1, createdCount.get())
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

    private object Utf8StringSerializer : Serializer<String> {
        override val defaultValue: String = ""

        override suspend fun readFrom(input: InputStream): String =
            input.readBytes().toString(Charsets.UTF_8)

        override suspend fun writeTo(t: String, output: OutputStream) {
            output.write(t.toByteArray(Charsets.UTF_8))
        }
    }
}
