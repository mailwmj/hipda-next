package net.jejer.hipda.data.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import dagger.Binds
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.security.AeadProvider
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CredentialsStoreAndroidTest {

    private lateinit var aeadProvider: AeadProvider

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        aeadProvider = TinkAeadProvider(InMemoryKeysetBackend())
    }

    @Test
    fun androidConstructor_persistsEncryptedFileUnderFilesDatastoreWithHxPrefix() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expectedFile = DataStoreCredentialsStore.credentialsDataStoreFile(context, aeadProvider)

        assertEquals(aeadProvider.encryptedFileName("credentials"), expectedFile.name)
        assertEquals("hx_credentials", expectedFile.name)
        assertEquals("datastore", expectedFile.parentFile?.name)
        assertEquals(context.filesDir, expectedFile.parentFile?.parentFile)

        val store: CredentialsStore = DataStoreCredentialsStore(context, aeadProvider)
        val account = SavedAccount(
            uid = "4242",
            username = "android_user",
            passwordMd5 = "fedcba9876543210fedcba9876543210",
            securityQuestionId = 2,
            securityAnswer = "secret_answer",
            lastLoginAt = 1_700_000_000_000L,
        )

        store.upsert(account)
        store.setActive(account.uid)

        assertEquals(listOf(account), store.accounts.first())
        assertEquals(account.uid, store.activeUid.first())
        assertTrue(expectedFile.exists())
        val raw = expectedFile.readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains(account.passwordMd5))
        assertFalse(raw.contains(account.securityAnswer))
    }

    @Test
    fun androidConstructor_recoversCorruptedFileAtDefaultPathAndAcceptsNewWrites() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val targetFile = DataStoreCredentialsStore.credentialsDataStoreFile(context, aeadProvider)
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))

        val store: CredentialsStore = DataStoreCredentialsStore(context, aeadProvider)

        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        val account = SavedAccount(
            uid = "5150",
            username = "recovered_android_user",
            passwordMd5 = "00112233445566778899aabbccddeeff",
            lastLoginAt = 2_000L,
        )
        store.upsert(account)
        assertEquals(listOf(account), store.accounts.first())
    }

    @Test
    fun hiltWiring_annotationsArePresentOnStoreAndModule() {
        assertTrue(
            "DataStoreCredentialsStore must be annotated with @Singleton",
            DataStoreCredentialsStore::class.java.isAnnotationPresent(Singleton::class.java),
        )
        val bindMethod = CredentialsModule::class.java.getDeclaredMethod(
            "bindCredentialsStore",
            DataStoreCredentialsStore::class.java,
        )
        assertTrue(
            "bindCredentialsStore must be annotated with @Binds",
            bindMethod.isAnnotationPresent(Binds::class.java),
        )
        assertTrue(
            "bindCredentialsStore must be annotated with @Singleton",
            bindMethod.isAnnotationPresent(Singleton::class.java),
        )
    }

    private class InMemoryKeysetBackend : KeysetBackend {
        private val aeads = mutableMapOf<String, Aead>()

        override fun buildAead(normalizedKeysetName: String): Aead =
            aeads.getOrPut(normalizedKeysetName) {
                KeysetHandle.generateNew(
                    PredefinedAeadParameters.AES256_GCM,
                    RegistryConfiguration.get(),
                ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            }

        override fun clearKeysetEntry(normalizedKeysetName: String) {
            aeads.remove(normalizedKeysetName)
        }

        override fun deleteMasterKeyAlias() {
            aeads.clear()
        }
    }
}
