package net.jejer.hipda.data.user

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BlacklistStoreTest {

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

    private fun newCacheFile(name: String = "blacklist_cache.preferences_pb"): File =
        File(tempFolder.newFolder(), name)

    @Test
    fun freshStore_returnsEmptySnapshotForAnyUid() = runTest {
        val store = BlacklistStore(createDataStore(newCacheFile(), backgroundScope))

        val snapshot = store.read("10001")
        assertEquals("10001", snapshot.ownerUid)
        assertEquals(emptySet<String>(), snapshot.usernames)
        assertNull(snapshot.lastSyncedAtMs)
    }

    @Test
    fun replaceAddRemoveAndClear_persistAcrossReopenedDataStore() = runTest {
        val file = newCacheFile()
        val scope1 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store1 = BlacklistStore(createDataStore(file, scope1))

            val replaced = store1.replace(
                ownerUid = "10001",
                usernames = listOf("  Bob  ", "Alice", "", "   ", "Bob", "测试用户"),
                syncedAtMs = 1_700_000_123_000L,
            )
            assertEquals("10001", replaced.ownerUid)
            assertEquals(setOf("Alice", "Bob", "测试用户"), replaced.usernames)
            assertEquals(1_700_000_123_000L, replaced.lastSyncedAtMs)

            val added = store1.add("10001", "  Charlie  ")
            assertEquals(setOf("Alice", "Bob", "Charlie", "测试用户"), added.usernames)
            assertEquals(1_700_000_123_000L, added.lastSyncedAtMs)

            val removed = store1.remove("10001", "Bob")
            assertEquals(setOf("Alice", "Charlie", "测试用户"), removed.usernames)
            assertEquals(1_700_000_123_000L, removed.lastSyncedAtMs)
        } finally {
            scope1.cancel()
            advanceUntilIdle()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store2 = BlacklistStore(createDataStore(file, scope2))
            val restored = store2.read("10001")
            assertEquals("10001", restored.ownerUid)
            assertEquals(setOf("Alice", "Charlie", "测试用户"), restored.usernames)
            assertEquals(1_700_000_123_000L, restored.lastSyncedAtMs)

            store2.clear("10001")
            val cleared = store2.read("10001")
            assertEquals(emptySet<String>(), cleared.usernames)
            assertNull(cleared.lastSyncedAtMs)
        } finally {
            scope2.cancel()
            advanceUntilIdle()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun perAccountBuckets_isolateMultipleUidsWithoutCrossAccountLeak() = runTest {
        val store = BlacklistStore(createDataStore(newCacheFile(), backgroundScope))

        store.replace("10001", listOf("SpammerA", "TrollA"), syncedAtMs = 100L)
        store.replace("10002", listOf("SpammerB"), syncedAtMs = 200L)

        assertEquals(BlacklistSnapshot("10001", setOf("SpammerA", "TrollA"), 100L), store.read("10001"))
        assertEquals(BlacklistSnapshot("10002", setOf("SpammerB"), 200L), store.read("10002"))
        assertEquals(BlacklistSnapshot("10003", emptySet(), null), store.read("10003"))

        store.clear("10001")
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), store.read("10001"))
        assertEquals(BlacklistSnapshot("10002", setOf("SpammerB"), 200L), store.read("10002"))
    }

    @Test
    fun corruptedProtoFileOnDisk_recoversToEmptyWithoutCrashing() = runTest {
        val file = newCacheFile()
        file.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x13, 0x7F, 0x0A))

        val store = BlacklistStore(createDataStore(file, backgroundScope))
        val snapshot = store.read("10001")
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), snapshot)

        // Store remains writable after corruption recovery
        val updated = store.add("10001", "RecoveredUser")
        assertEquals(setOf("RecoveredUser"), updated.usernames)
    }

    @Test
    fun corruptedJsonAndWrongPreferenceType_recoverToEmptyWithoutCrashing() = runTest {
        val dataStore = createDataStore(newCacheFile(), backgroundScope)
        val store = BlacklistStore(dataStore)

        // Malformed JSON string
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("blacklist_accounts_json")] = "{not-valid-json"
        }
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), store.read("10001"))

        // Wrong primitive type stored under the key name
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[intPreferencesKey("blacklist_accounts_json")] = 12345
        }
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), store.read("10001"))

        // Partial corruption in one bucket does not break valid sibling bucket
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[stringPreferencesKey("blacklist_accounts_json")] =
                """{"10001":{"usernames":"not_an_array","lastSyncedAtMs":"bad"},"10002":{"usernames":["ValidUser"],"lastSyncedAtMs":999}}"""
        }
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), store.read("10001"))
        assertEquals(BlacklistSnapshot("10002", setOf("ValidUser"), 999L), store.read("10002"))
    }

    @Test
    fun ioExceptionFromDataStore_emitsEmptySnapshotWithoutThrowing() = runTest {
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("Simulated blacklist_cache read error")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = emptyPreferences()
        }

        val store = BlacklistStore(failingDataStore)
        val snapshot = store.observe("10001").first()
        assertEquals(BlacklistSnapshot("10001", emptySet(), null), snapshot)
    }
}
