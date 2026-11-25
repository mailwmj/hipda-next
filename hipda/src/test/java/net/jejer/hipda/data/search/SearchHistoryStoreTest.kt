package net.jejer.hipda.data.search

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.di.SearchAndFavoriteModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SearchHistoryStoreTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun createDataStore(
        file: File = File(tempFolder.newFolder(), "search_history.preferences_pb"),
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler(
            produceNewData = { emptyPreferences() },
        ),
        scope = scope,
        produceFile = { file },
    )

    @Test
    fun record_trimsDeduplicatesMovesToFrontAndIgnoresBlankQueries() = runBlocking {
        val store = SearchHistoryStore(createDataStore())

        store.history.test {
            assertEquals(emptyList<String>(), awaitItem())

            assertEquals(emptyList<String>(), store.record("   "))
            assertEquals(listOf("ThinkPad"), store.record("  ThinkPad  "))
            assertEquals(listOf("ThinkPad"), awaitItem())

            assertEquals(listOf("折叠屏", "ThinkPad"), store.record("折叠屏"))
            assertEquals(listOf("折叠屏", "ThinkPad"), awaitItem())

            assertEquals(listOf("机械键盘", "折叠屏", "ThinkPad"), store.record("机械键盘"))
            assertEquals(listOf("机械键盘", "折叠屏", "ThinkPad"), awaitItem())

            // Re-recording an existing item moves it to the head without duplicating
            assertEquals(listOf("ThinkPad", "机械键盘", "折叠屏"), store.record("ThinkPad"))
            assertEquals(listOf("ThinkPad", "机械键盘", "折叠屏"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun record_enforcesDefaultMaxTwentyItemsCap() = runBlocking {
        val store = SearchHistoryStore(createDataStore())

        for (i in 1..25) {
            store.record("query-$i")
        }

        val items = store.history.first()
        assertEquals(SearchHistoryStore.DEFAULT_MAX_ITEMS, items.size)
        assertEquals(20, items.size)
        assertEquals("query-25", items.first())
        assertEquals("query-6", items.last())

        // Re-recording the oldest retained item promotes it to index 0 while retaining all 20
        store.record("query-6")
        val promoted = store.history.first()
        assertEquals(20, promoted.size)
        assertEquals("query-6", promoted.first())
        assertEquals("query-25", promoted[1])
        assertEquals("query-7", promoted.last())
    }

    @Test
    fun removeRestoreAndClearAll_preserveExactIndexForSnackbarUndo() = runBlocking {
        val store = SearchHistoryStore(createDataStore())
        store.record("oldest")
        store.record("middle")
        store.record("newest")
        assertEquals(listOf("newest", "middle", "oldest"), store.history.first())

        // Removing missing or blank query returns null
        assertNull(store.remove("   "))
        assertNull(store.remove("nonexistent"))

        // Remove middle item -> returns original index 1
        val removed = store.remove("  middle  ")
        assertEquals(RemovedSearchHistoryEntry(query = "middle", index = 1), removed)
        assertEquals(listOf("newest", "oldest"), store.history.first())

        // Undo restores to exact original index 1
        val restored = store.restore(removed!!.query, removed.index)
        assertEquals(listOf("newest", "middle", "oldest"), restored)
        assertEquals(listOf("newest", "middle", "oldest"), store.history.first())

        // Out-of-bounds index clamps cleanly, and restoring an existing item moves without duplicating
        assertEquals(listOf("newest", "middle", "oldest", "tail"), store.restore("tail", 999))
        assertEquals(listOf("head", "newest", "middle", "oldest", "tail"), store.restore("head", -5))
        assertEquals(listOf("head", "middle", "newest", "oldest", "tail"), store.restore("middle", 1))

        store.clearAll()
        assertEquals(emptyList<String>(), store.history.first())
    }

    @Test
    fun s4_4_restoreAtFullTwentyItemCapacityEnforcesMaxItemsCap() = runBlocking {
        val store = SearchHistoryStore(createDataStore())
        for (i in 1..20) {
            store.record("q-$i")
        }
        assertEquals(20, store.history.first().size)
        assertEquals("q-20", store.history.first().first())
        assertEquals("q-1", store.history.first().last())

        val restored = store.restore("restored-query", 2)
        assertEquals(20, restored.size)
        assertEquals("restored-query", restored[2])
        assertEquals("q-20", restored.first())
        assertEquals("q-2", restored.last())
        assertEquals(restored, store.history.first())
    }

    @Test
    fun decodeAndReadFlow_recoversFromMalformedJsonAndIoException() = runBlocking {
        // 1. Malformed JSON value in preferences falls back to empty list
        val backingStore = createDataStore()
        backingStore.edit { prefs ->
            prefs[SearchHistoryPreferenceKeys.QUERIES_JSON] = "{not-a-json-array"
        }
        val store = SearchHistoryStore(backingStore)
        assertEquals(emptyList<String>(), store.history.first())

        // Subsequent write overwrites corrupted JSON cleanly
        store.record("恢复正常")
        assertEquals(listOf("恢复正常"), store.history.first())

        // 2. IOException from DataStore.data falls back to empty list
        val ioFailingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk read failed") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk write failed")
        }
        val ioFallbackStore = SearchHistoryStore(ioFailingDataStore)
        assertEquals(emptyList<String>(), ioFallbackStore.history.first())

        // 3. Non-IOException is rethrown rather than swallowed
        val illegalFailingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("unexpected bug") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IllegalStateException("unexpected bug")
        }
        val throwingStore = SearchHistoryStore(illegalFailingDataStore)
        var caughtIllegal = false
        try {
            throwingStore.history.first()
        } catch (e: IllegalStateException) {
            caughtIllegal = e.message == "unexpected bug"
        }
        assertTrue("Expected non-IOException to propagate", caughtIllegal)
    }

    @Test
    fun productionModuleProvideSearchHistoryDataStore_recoversFromCorruptedProtobufFile() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storeFile = SearchAndFavoriteModule.searchHistoryDataStoreFile(context)
        assertEquals("search_history.preferences_pb", storeFile.name)

        storeFile.parentFile?.mkdirs()
        storeFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x13, 0x37, 0x7F, 0xAA.toByte()))

        // Directly call SearchAndFavoriteModule.provideSearchHistoryDataStore(context)
        val productionDataStore = SearchAndFavoriteModule.provideSearchHistoryDataStore(context)
        // Reading raw DataStore directly verifies ReplaceFileCorruptionHandler is wired in production module
        assertEquals(emptyPreferences(), productionDataStore.data.first())

        val store = SearchHistoryStore(productionDataStore)
        store.record("损坏恢复测试")
        assertEquals(listOf("损坏恢复测试"), store.history.first())
    }
}
