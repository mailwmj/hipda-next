package net.jejer.hipda.data.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
