package net.jejer.hipda.data.network.cookie

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import dagger.Provides
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.security.AeadProvider
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CookieStoreModuleTest {

    private lateinit var aeadProvider: AeadProvider

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        TinkAeadProvider.ensureTinkRegistered()
        aeadProvider = TinkAeadProvider(InMemoryKeysetBackend())
    }

    @Test
    fun provideSiteCookieStore_usesHxEncryptedFileInDataStoreDir() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expectedFile = File(context.filesDir, "datastore/hx_site_cookies")

        assertEquals(expectedFile, CookieStoreModule.cookieStoreFile(context, aeadProvider))

        val store = CookieStoreModule.provideSiteCookieStore(context, aeadProvider) as DataStoreSiteCookieStore
        val url = "https://www.4d4y.com/forum/".toHttpUrl()
        val secretValue = "robo_secret_cdb_auth_token"

        store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_auth=$secretValue; Path=/"))))
        store.flush()

        assertTrue(expectedFile.exists())
        val raw = expectedFile.readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains(secretValue))
        assertEquals(listOf(secretValue), store.loadForRequest(url).map { it.value })
    }

    @Test
    fun provideSiteCookieStore_recoversFromCorruptedFileOnDisk() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.filesDir, "datastore/hx_site_cookies")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))

        val store = CookieStoreModule.provideSiteCookieStore(context, aeadProvider) as DataStoreSiteCookieStore
        val url = "https://www.4d4y.com/forum/".toHttpUrl()

        assertTrue(store.loadForRequest(url).isEmpty())

        store.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "cdb_sid=ok; Path=/"))))
        store.flush()
        assertEquals(listOf("ok"), store.loadForRequest(url).map { it.value })
    }

    @Test
    fun provideSiteCookieStore_isAnnotatedWithProvidesAndSingleton() {
        val method = CookieStoreModule::class.java.getDeclaredMethod(
            "provideSiteCookieStore",
            Context::class.java,
            AeadProvider::class.java,
        )

        assertTrue(
            "provideSiteCookieStore must be annotated with @Provides",
            method.isAnnotationPresent(Provides::class.java),
        )
        assertTrue(
            "provideSiteCookieStore must be annotated with @Singleton",
            method.isAnnotationPresent(Singleton::class.java),
        )
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
}
