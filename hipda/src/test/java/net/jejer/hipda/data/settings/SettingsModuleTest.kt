package net.jejer.hipda.data.settings

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import dagger.Provides
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.di.SettingsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsModuleTest {

    @Test
    fun provideSettingsDataStore_usesExpectedPreferencesPbFileNameAndPersists() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expectedFile = SettingsModule.settingsDataStoreFile(context)

        assertEquals("settings.preferences_pb", expectedFile.name)
        assertEquals("datastore", expectedFile.parentFile?.name)

        val dataStore = SettingsModule.provideSettingsDataStore(context)
        val repository = SettingsModule.provideSettingsRepository(dataStore)

        assertEquals(AppSettings(), repository.settings.first())
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
        assertTrue(expectedFile.exists())
    }

    @Test
    fun provideSettingsDataStore_recoversFromCorruptedFileAndAllowsSubsequentWrites() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settingsFile = SettingsModule.settingsDataStoreFile(context)
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x13, 0x7F))

        val dataStore = SettingsModule.provideSettingsDataStore(context)
        val repository = SettingsModule.provideSettingsRepository(dataStore)

        assertEquals(AppSettings(), repository.settings.first())
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
    }

    @Test
    fun settingsModuleProviders_areAnnotatedWithSingleton() {
        val provideDataStore = SettingsModule::class.java.getDeclaredMethod(
            "provideSettingsDataStore",
            Context::class.java,
        )
        val provideRepository = SettingsModule::class.java.getDeclaredMethod(
            "provideSettingsRepository",
            DataStore::class.java,
        )

        assertTrue(
            "provideSettingsDataStore must be annotated with @Provides",
            provideDataStore.isAnnotationPresent(Provides::class.java),
        )
        assertTrue(
            "provideSettingsDataStore must be annotated with @Singleton",
            provideDataStore.isAnnotationPresent(Singleton::class.java),
        )
        assertTrue(
            "provideSettingsRepository must be annotated with @Provides",
            provideRepository.isAnnotationPresent(Provides::class.java),
        )
        assertTrue(
            "provideSettingsRepository must be annotated with @Singleton",
            provideRepository.isAnnotationPresent(Singleton::class.java),
        )
    }
}
