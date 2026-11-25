package net.jejer.hipda.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun createDataStore(
        file: File,
        scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler(
            produceNewData = { emptyPreferences() },
        ),
        scope = scope,
        produceFile = { file },
    )

    private fun newSettingsFile(name: String = "settings.preferences_pb"): File =
        File(tempFolder.newFolder(), name)

    @Test
    fun freshInstall_emitsAllDefaultSettings() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            val settings = awaitItem()
            assertEquals(AppSettings(), settings)

            // 外观与阅读
            assertEquals(ThemeMode.SYSTEM, settings.themeMode)
            assertFalse(settings.pureBlack)
            assertEquals(AppSettings.THEME_SEED_WALLPAPER, settings.themeSeed)
            assertEquals(2, settings.fontStep)
            assertEquals(LineSpacing.STANDARD, settings.lineSpacing)

            // 论坛与发帖
            assertEquals(listOf(2, 6, 7), settings.followedForums)
            assertTrue(settings.showStickyThreads)
            assertEquals(emptyMap<Int, Boolean>(), settings.sortByPostTime)
            assertFalse(settings.isSortByPostTime(2))
            assertFalse(settings.tailEnabled)
            assertEquals("", settings.tailText)
            assertEquals("", settings.tailUrl)

            // 通知与免打扰
            assertTrue(settings.notificationsEnabled)
            assertFalse(settings.silentModeEnabled)
            assertEquals(22 * 60, settings.silentBeginMinutes)
            assertEquals(8 * 60, settings.silentEndMinutes)

            // 网络与缓存
            assertEquals("https://www.4d4y.com", settings.forumServer)
            assertEquals(500, settings.imageCacheLimitMb)

            // 界面状态
            assertEquals(PaneSplit.DEFAULT, settings.paneSplitPortrait)
            assertEquals(PaneSplit.DEFAULT, settings.paneSplitLandscape)
            assertFalse(settings.sidebarCollapsedPortrait)
            assertFalse(settings.sidebarCollapsedLandscape)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun appearanceSetters_emitUpdatedFlowValues() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            assertEquals(AppSettings(), awaitItem())

            repository.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem().themeMode)

            repository.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem().themeMode)

            repository.setPureBlack(true)
            assertTrue(awaitItem().pureBlack)

            repository.setThemeSeed("ocean_blue")
            assertEquals("ocean_blue", awaitItem().themeSeed)

            repository.setFontStep(4)
            assertEquals(4, awaitItem().fontStep)

            repository.setLineSpacing(LineSpacing.LOOSE)
            assertEquals(LineSpacing.LOOSE, awaitItem().lineSpacing)

            repository.setLineSpacing(LineSpacing.COMPACT)
            assertEquals(LineSpacing.COMPACT, awaitItem().lineSpacing)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forumAndPostingSetters_emitUpdatedFlowValues() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            assertEquals(AppSettings(), awaitItem())

            repository.setFollowedForums(listOf(59, 7, 2))
            assertEquals(listOf(59, 7, 2), awaitItem().followedForums)

            repository.setShowStickyThreads(false)
            assertFalse(awaitItem().showStickyThreads)

            repository.setSortByPostTime(fid = 2, sortByPostTime = true)
            val afterFirstSort = awaitItem()
            assertEquals(mapOf(2 to true), afterFirstSort.sortByPostTime)
            assertTrue(afterFirstSort.isSortByPostTime(2))

            repository.setSortByPostTime(fid = 6, sortByPostTime = false)
            assertEquals(mapOf(2 to true, 6 to false), awaitItem().sortByPostTime)

            repository.setSortByPostTime(fid = 2, sortByPostTime = false)
            val afterToggleSort = awaitItem()
            assertEquals(mapOf(2 to false, 6 to false), afterToggleSort.sortByPostTime)
            assertFalse(afterToggleSort.isSortByPostTime(2))

            repository.setTailEnabled(true)
            assertTrue(awaitItem().tailEnabled)

            repository.setTailText("来自 hipda-next")
            assertEquals("来自 hipda-next", awaitItem().tailText)

            repository.setTailUrl("https://www.4d4y.com/forum/viewthread.php?tid=1579403")
            assertEquals(
                "https://www.4d4y.com/forum/viewthread.php?tid=1579403",
                awaitItem().tailUrl,
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun notificationNetworkAndUiStateSetters_emitUpdatedFlowValues() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            assertEquals(AppSettings(), awaitItem())

            repository.setNotificationsEnabled(false)
            assertFalse(awaitItem().notificationsEnabled)

            repository.setSilentModeEnabled(true)
            assertTrue(awaitItem().silentModeEnabled)

            repository.setSilentBeginMinutes(23 * 60 + 30)
            assertEquals(23 * 60 + 30, awaitItem().silentBeginMinutes)

            repository.setSilentEndMinutes(7 * 60 + 15)
            assertEquals(7 * 60 + 15, awaitItem().silentEndMinutes)

            repository.setForumServer("https://mirror.4d4y.com/")
            assertEquals("https://mirror.4d4y.com", awaitItem().forumServer)

            repository.setImageCacheLimitMb(1024)
            assertEquals(1024, awaitItem().imageCacheLimitMb)

            repository.setPaneSplitPortrait(PaneSplit.COLLAPSED)
            assertEquals(PaneSplit.COLLAPSED, awaitItem().paneSplitPortrait)

            repository.setPaneSplitLandscape(PaneSplit.BALANCED)
            assertEquals(PaneSplit.BALANCED, awaitItem().paneSplitLandscape)

            repository.setSidebarCollapsedPortrait(true)
            assertTrue(awaitItem().sidebarCollapsedPortrait)

            repository.setSidebarCollapsedLandscape(true)
            assertTrue(awaitItem().sidebarCollapsedLandscape)

            repository.setSidebarCollapsedPortrait(false)
            assertFalse(awaitItem().sidebarCollapsedPortrait)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recreatingRepository_persistsAllModifiedValues() = runTest {
        val file = newSettingsFile()
        val scope1 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val expected = try {
            val repo1: SettingsRepository = DataStoreSettingsRepository(createDataStore(file, scope1))

            repo1.setThemeMode(ThemeMode.DARK)
            repo1.setPureBlack(true)
            repo1.setThemeSeed("emerald")
            repo1.setFontStep(3)
            repo1.setLineSpacing(LineSpacing.LOOSE)
            repo1.setFollowedForums(listOf(60, 7, 59, 2))
            repo1.setShowStickyThreads(false)
            repo1.setSortByPostTime(2, true)
            repo1.setSortByPostTime(7, false)
            repo1.setTailEnabled(true)
            repo1.setTailText("Android")
            repo1.setTailUrl("https://www.4d4y.com")
            repo1.setNotificationsEnabled(false)
            repo1.setSilentModeEnabled(true)
            repo1.setSilentBeginMinutes(21 * 60)
            repo1.setSilentEndMinutes(6 * 60 + 30)
            repo1.setForumServer("https://alt.4d4y.com")
            repo1.setImageCacheLimitMb(300)
            repo1.setPaneSplitPortrait(PaneSplit.COLLAPSED)
            repo1.setPaneSplitLandscape(PaneSplit.BALANCED)
            repo1.setSidebarCollapsedPortrait(true)
            repo1.setSidebarCollapsedLandscape(true)

            repo1.settings.first()
        } finally {
            scope1.cancel()
            advanceUntilIdle()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val repo2: SettingsRepository = DataStoreSettingsRepository(createDataStore(file, scope2))
            val restored = repo2.settings.first()
            assertEquals(expected, restored)
            assertEquals(listOf(60, 7, 59, 2), restored.followedForums)
            assertEquals(mapOf(2 to true, 7 to false), restored.sortByPostTime)
            assertEquals(PaneSplit.COLLAPSED, restored.paneSplitPortrait)
            assertEquals(PaneSplit.BALANCED, restored.paneSplitLandscape)
            assertTrue(restored.sidebarCollapsedPortrait)
            assertTrue(restored.sidebarCollapsedLandscape)
        } finally {
            scope2.cancel()
            advanceUntilIdle()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun concurrentSetSortByPostTime_preservesAllForumUpdates() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = createDataStore(newSettingsFile(), scope)
            val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)
            val fids = listOf(2, 6, 7, 12, 22, 23, 24, 25, 50, 56, 57, 59, 60, 62, 63)

            coroutineScope {
                fids.forEach { fid ->
                    launch(Dispatchers.Default) {
                        repository.setSortByPostTime(fid, true)
                    }
                }
            }

            assertEquals(fids.associateWith { true }, repository.settings.first().sortByPostTime)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun fontStep_clampsOutOfRangeValuesOnWriteAndRead() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            assertEquals(2, awaitItem().fontStep)

            repository.setFontStep(-5)
            assertEquals(0, awaitItem().fontStep)

            repository.setFontStep(99)
            assertEquals(4, awaitItem().fontStep)

            repository.setFontStep(1)
            assertEquals(1, awaitItem().fontStep)

            // Manually write out-of-range values directly into DataStore.
            dataStore.edit { prefs -> prefs[SettingsPreferenceKeys.FONT_STEP] = -100 }
            assertEquals(0, awaitItem().fontStep)

            dataStore.edit { prefs -> prefs[SettingsPreferenceKeys.FONT_STEP] = 42 }
            assertEquals(4, awaitItem().fontStep)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun invalidEnumStrings_fallbackToDefaultsWithoutThrowing() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        dataStore.edit { prefs ->
            prefs[SettingsPreferenceKeys.THEME_MODE] = "SEPIA_MODE"
            prefs[SettingsPreferenceKeys.LINE_SPACING] = "EXTRA_WIDE"
            prefs[SettingsPreferenceKeys.PANE_SPLIT_PORTRAIT] = "UNKNOWN_SPLIT"
            prefs[SettingsPreferenceKeys.PANE_SPLIT_LANDSCAPE] = "FULLSCREEN"
        }

        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)
        val settings = repository.settings.first()

        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(LineSpacing.STANDARD, settings.lineSpacing)
        assertEquals(PaneSplit.DEFAULT, settings.paneSplitPortrait)
        assertEquals(PaneSplit.DEFAULT, settings.paneSplitLandscape)
    }

    @Test
    fun corruptedValuesAndWrongTypes_fallbackToDefaultsWithoutThrowing() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        dataStore.edit { prefs ->
            prefs[SettingsPreferenceKeys.THEME_SEED] = "   "
            prefs[SettingsPreferenceKeys.FOLLOWED_FORUMS] = "not-a-json-array"
            prefs[SettingsPreferenceKeys.SORT_BY_POST_TIME] = "{\"bad_fid\":true}"
            prefs[SettingsPreferenceKeys.SILENT_BEGIN_MINUTES] = -1
            prefs[SettingsPreferenceKeys.SILENT_END_MINUTES] = 1440
            prefs[SettingsPreferenceKeys.FORUM_SERVER] = "   "
            prefs[SettingsPreferenceKeys.IMAGE_CACHE_LIMIT_MB] = 0
            // Wrong primitive types stored under known preference key names.
            prefs[stringPreferencesKey(SettingsPreferenceKeys.FONT_STEP.name)] = "corrupt_int"
            prefs[intPreferencesKey(SettingsPreferenceKeys.THEME_MODE.name)] = 999
            prefs[stringPreferencesKey(SettingsPreferenceKeys.PURE_BLACK.name)] = "true"
            prefs[stringPreferencesKey(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_PORTRAIT.name)] = "yes"
            prefs[intPreferencesKey(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_LANDSCAPE.name)] = 1
        }

        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)
        val settings = repository.settings.first()

        assertEquals(AppSettings(), settings)
    }

    @Test
    fun corruptedFollowedForumsAndSortPayloads_fallbackToDefaults() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        // Negative fid in followedForums JSON array
        dataStore.edit { prefs ->
            prefs[SettingsPreferenceKeys.FOLLOWED_FORUMS] = "[2, -1, 6]"
            prefs[SettingsPreferenceKeys.SORT_BY_POST_TIME] = "{\"2\":\"true\"}"
        }
        val first = repository.settings.first()
        assertEquals(AppSettings.DEFAULT_FOLLOWED_FORUMS, first.followedForums)
        assertEquals(emptyMap<Int, Boolean>(), first.sortByPostTime)

        // String element in followedForums JSON array
        dataStore.edit { prefs ->
            prefs[SettingsPreferenceKeys.FOLLOWED_FORUMS] = "[\"2\", 6]"
            prefs[SettingsPreferenceKeys.SORT_BY_POST_TIME] = "[1, 2]"
        }
        val second = repository.settings.first()
        assertEquals(AppSettings.DEFAULT_FOLLOWED_FORUMS, second.followedForums)
        assertEquals(emptyMap<Int, Boolean>(), second.sortByPostTime)
    }

    @Test
    fun corruptedProtoFileOnDisk_recoversToDefaultsWithoutThrowing() = runTest {
        val file = newSettingsFile()
        file.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x13, 0x7F))

        val dataStore = createDataStore(file, backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        assertEquals(AppSettings(), repository.settings.first())
    }

    @Test
    fun ioExceptionFromDataStore_emitsDefaultsWithoutThrowing() = runTest {
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("Simulated disk read error")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = emptyPreferences()
        }

        val repository: SettingsRepository = DataStoreSettingsRepository(failingDataStore)
        assertEquals(AppSettings(), repository.settings.first())
    }

    @Test
    fun followedForums_preservesOrderDeduplicatesAndAllowsEmptyList() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.settings.test {
            assertEquals(listOf(2, 6, 7), awaitItem().followedForums)

            repository.setFollowedForums(listOf(60, 7, 2, 59, 6))
            assertEquals(listOf(60, 7, 2, 59, 6), awaitItem().followedForums)

            repository.setFollowedForums(listOf(6, 59, 2, 7, 60))
            assertEquals(listOf(6, 59, 2, 7, 60), awaitItem().followedForums)

            repository.setFollowedForums(listOf(7, 2, 7, 0, -3, 6))
            assertEquals(listOf(7, 2, 6), awaitItem().followedForums)

            repository.setFollowedForums(emptyList())
            assertEquals(emptyList<Int>(), awaitItem().followedForums)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setters_normalizeInvalidInputBoundaries() = runTest {
        val dataStore = createDataStore(newSettingsFile(), backgroundScope)
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        repository.setSilentBeginMinutes(-10)
        repository.setSilentEndMinutes(2000)
        repository.setThemeSeed("   ")
        repository.setForumServer("   ")
        repository.setImageCacheLimitMb(-50)

        val settings = repository.settings.first()
        assertEquals(0, settings.silentBeginMinutes)
        assertEquals(AppSettings.MAX_MINUTES_OF_DAY, settings.silentEndMinutes)
        assertEquals(AppSettings.THEME_SEED_WALLPAPER, settings.themeSeed)
        assertEquals(AppSettings.DEFAULT_FORUM_SERVER, settings.forumServer)
        assertEquals(AppSettings.DEFAULT_IMAGE_CACHE_LIMIT_MB, settings.imageCacheLimitMb)
    }
}
