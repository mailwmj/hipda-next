package net.jejer.hipda.data.account

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import dagger.Provides
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun credentialsModule_persistsEncryptedFileUnderFilesDatastoreWithHxPrefix() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expectedFile = CredentialsModule.credentialsDataStoreFile(context, aeadProvider)

        assertEquals(aeadProvider.encryptedFileName("credentials"), expectedFile.name)
        assertEquals("hx_credentials", expectedFile.name)
        assertEquals("datastore", expectedFile.parentFile?.name)
        assertEquals(context.filesDir, expectedFile.parentFile?.parentFile)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = CredentialsModule.createCredentialsStore(context, aeadProvider, scope)
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
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun credentialsModule_recoversCorruptedFileAtDefaultPathAndAcceptsNewWrites() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val targetFile = CredentialsModule.credentialsDataStoreFile(context, aeadProvider)
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = CredentialsModule.createCredentialsStore(context, aeadProvider, scope)

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
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun hiltWiring_singletonsDataStoreCreationInModuleAndNarrowsStoreConstructor() {
        val provideMethod = CredentialsModule::class.java.getDeclaredMethod(
            "provideCredentialsStore",
            Context::class.java,
            AeadProvider::class.java,
        )
        assertTrue(
            "provideCredentialsStore must be annotated with @Provides",
            provideMethod.isAnnotationPresent(Provides::class.java),
        )
        assertTrue(
            "provideCredentialsStore must be annotated with @Singleton",
            provideMethod.isAnnotationPresent(Singleton::class.java),
        )

        val constructors = DataStoreCredentialsStore::class.java.declaredConstructors
        assertEquals(
            "DataStoreCredentialsStore must only accept DataStore<CredentialsSnapshot> to prevent bypassing Hilt",
            1,
            constructors.size,
        )
        assertEquals(
            listOf(DataStore::class.java),
            constructors.single().parameterTypes.toList(),
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
