package net.jejer.hipda.data.favorite

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
class FavoriteCacheStoreTest {

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
        file: File = File(tempFolder.newFolder(), "favorite_cache.preferences_pb"),
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler(
            produceNewData = { emptyPreferences() },
        ),
        scope = scope,
        produceFile = { file },
    )

    @Test
    fun buckets_isolateAccountsAndKindsSeparatelyAcrossReplaceMergeAddRemoveAndClear() = runBlocking {
        val store = FavoriteCacheStore(createDataStore())

        store.observe("10001").test {
            assertEquals(FavoriteCacheSnapshot(ownerUid = "10001"), awaitItem())

            // Replace favorites for account 10001 (invalid tids filtered out)
            store.replace(
                ownerUid = "10001",
                kind = FavoriteKind.FAVORITE,
                tids = listOf("3468951", "0", "-5", "abc", "3470589", "3468951"),
                syncedAtMs = 1_000L,
            )
            val afterReplaceFav = awaitItem()
            assertEquals(setOf("3468951", "3470589"), afterReplaceFav.favoriteTids)
            assertEquals(emptySet<String>(), afterReplaceFav.attentionTids)
            assertEquals(1_000L, afterReplaceFav.lastFavoriteSyncedAtMs)
            assertNull(afterReplaceFav.lastAttentionSyncedAtMs)

            // Add attention tid -> does not touch favoriteTids
            store.add("10001", FavoriteKind.ATTENTION, "3209656")
            val afterAddAtt = awaitItem()
            assertEquals(setOf("3468951", "3470589"), afterAddAtt.favoriteTids)
            assertEquals(setOf("3209656"), afterAddAtt.attentionTids)

            // Merge extra favorite tids -> preserves existing and sync timestamp
            store.merge("10001", FavoriteKind.FAVORITE, listOf("2710506", "bad"))
            val afterMerge = awaitItem()
            assertEquals(setOf("2710506", "3468951", "3470589"), afterMerge.favoriteTids)
            assertEquals(1_000L, afterMerge.lastFavoriteSyncedAtMs)

            // Remove single favorite tid
            store.remove("10001", FavoriteKind.FAVORITE, "3468951")
            val afterRemove = awaitItem()
            assertEquals(setOf("2710506", "3470589"), afterRemove.favoriteTids)
            assertEquals(setOf("3209656"), afterRemove.attentionTids)

            cancelAndIgnoreRemainingEvents()
        }

        // Write account 10002 -> strictly isolated from 10001
        store.replace(
            ownerUid = "10002",
            kind = FavoriteKind.FAVORITE,
            tids = listOf("999001"),
            syncedAtMs = 2_000L,
        )
        assertEquals(setOf("2710506", "3470589"), store.read("10001").favoriteTids)
        assertEquals(setOf("3209656"), store.read("10001").attentionTids)
        assertEquals(setOf("999001"), store.read("10002").favoriteTids)
        assertEquals(emptySet<String>(), store.read("10002").attentionTids)

        // Clear account 10001 leaves 10002 intact
        store.clear("10001")
        assertEquals(emptySet<String>(), store.read("10001").favoriteTids)
        assertEquals(emptySet<String>(), store.read("10001").attentionTids)
        assertEquals(setOf("999001"), store.read("10002").favoriteTids)

        store.clearAll()
        assertEquals(emptySet<String>(), store.read("10002").favoriteTids)
    }

    @Test
    fun decodeAndReadFlow_recoversFromMalformedJsonAndIoException() = runBlocking {
        val backingStore = createDataStore()
        backingStore.edit { prefs ->
            prefs[FavoriteCachePreferenceKeys.ACCOUNTS_JSON] = "not-json-at-all"
        }
        val store = FavoriteCacheStore(backingStore)
        assertEquals(FavoriteCacheSnapshot(ownerUid = "10001"), store.read("10001"))

        store.add("10001", FavoriteKind.FAVORITE, "12345")
        assertEquals(setOf("12345"), store.read("10001").favoriteTids)

        // IOException falls back to empty snapshot
        val ioFailingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read boom") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("write boom")
        }
        assertEquals(FavoriteCacheSnapshot(ownerUid = "10001"), FavoriteCacheStore(ioFailingDataStore).read("10001"))

        // Non-IOException propagates
        val throwingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("unexpected") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IllegalStateException("unexpected")
        }
        var caught = false
        try {
            FavoriteCacheStore(throwingDataStore).read("10001")
        } catch (e: IllegalStateException) {
            caught = e.message == "unexpected"
        }
        assertTrue("Expected non-IOException to propagate", caught)
    }

    @Test
    fun productionModuleProvideFavoriteCacheDataStore_recoversFromCorruptedProtobufFile() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storeFile = SearchAndFavoriteModule.favoriteCacheDataStoreFile(context)
        assertEquals("favorite_cache.preferences_pb", storeFile.name)

        storeFile.parentFile?.mkdirs()
        storeFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x22, 0x33, 0x7F, 0x55))

        // Directly invoke production SearchAndFavoriteModule provider
        val productionDataStore = SearchAndFavoriteModule.provideFavoriteCacheDataStore(context)
        assertEquals(emptyPreferences(), productionDataStore.data.first())

        val store = FavoriteCacheStore(productionDataStore)
        store.add("10000", FavoriteKind.FAVORITE, "3468951")
        assertEquals(setOf("3468951"), store.read("10000").favoriteTids)
    }
}
