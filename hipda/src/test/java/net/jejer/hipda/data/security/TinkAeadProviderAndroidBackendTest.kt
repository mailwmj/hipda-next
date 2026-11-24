package net.jejer.hipda.data.security

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TinkAeadProviderAndroidBackendTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AeadProvider.KEYSET_PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun androidKeysetBackend_clearsCorruptedSharedPrefEntryAndRebuilds() {
        val prefs = context.getSharedPreferences(AeadProvider.KEYSET_PREF_FILE, Context.MODE_PRIVATE)
        val keysetName = AeadProvider.DEFAULT_KEYSET_NAME
        prefs.edit().putString(keysetName, "corrupted-non-hex-keyset").commit()

        val loadAttempts = AtomicInteger(0)
        val masterKeyDeletes = AtomicInteger(0)
        val backend = AndroidKeysetBackend(
            context = context,
            keysetAeadLoader = { appContext, normalizedKeysetName ->
                loadAttempts.incrementAndGet()
                val sp = appContext.getSharedPreferences(AeadProvider.KEYSET_PREF_FILE, Context.MODE_PRIVATE)
                val existing = sp.getString(normalizedKeysetName, null)
                if (existing == "corrupted-non-hex-keyset") {
                    throw GeneralSecurityException("Simulated AndroidKeysetManager decrypt failure")
                }
                sp.edit().putString(normalizedKeysetName, "rebuilt-valid-keyset").commit()
                KeysetHandle.generateNew(
                    PredefinedAeadParameters.AES256_GCM,
                    RegistryConfiguration.get(),
                ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            },
            masterKeyDeleter = { masterKeyDeletes.incrementAndGet() },
        )
        val provider = TinkAeadProvider(backend)

        val aead = provider.getOrCreateAead()
        val aad = provider.associatedDataFor("cookies")
        val encrypted = aead.encrypt("session=ok".toByteArray(), aad)

        assertArrayEquals("session=ok".toByteArray(), aead.decrypt(encrypted, aad))
        assertEquals(2, loadAttempts.get())
        assertEquals(0, masterKeyDeletes.get())
        assertEquals("rebuilt-valid-keyset", prefs.getString(keysetName, null))
        assertNotEquals("corrupted-non-hex-keyset", prefs.getString(keysetName, null))
    }

    @Test
    fun androidKeysetBackend_deletesMasterKeyAndFailsClosedWhenKeystoreBroken() {
        val prefs = context.getSharedPreferences(AeadProvider.KEYSET_PREF_FILE, Context.MODE_PRIVATE)
        val keysetName = "hx_credentials_keyset"
        prefs.edit().putString(keysetName, "stale-entry").commit()

        val loadAttempts = AtomicInteger(0)
        val masterKeyDeletes = AtomicInteger(0)
        val backend = AndroidKeysetBackend(
            context = context,
            keysetAeadLoader = { _, _ ->
                loadAttempts.incrementAndGet()
                throw KeyStoreException("Simulated unrecoverable AndroidKeyStore failure")
            },
            masterKeyDeleter = { masterKeyDeletes.incrementAndGet() },
        )
        val provider = TinkAeadProvider(backend)

        assertThrows(KeyStoreException::class.java) {
            provider.getOrCreateAead(keysetName)
        }
        assertEquals(3, loadAttempts.get())
        assertEquals(1, masterKeyDeletes.get())
        assertEquals(null, prefs.getString(keysetName, null))
    }
}
