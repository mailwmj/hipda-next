package net.jejer.hipda.data.message

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NotifyReadStateStoreTest {

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

    private fun newStoreFile(name: String = "notify_read_state.preferences_pb"): File =
        File(tempFolder.newFolder(), name)

    @Test
    fun freshStore_returnsEmptyDefaultStateForAccount() = runTest {
        val store = NotifyReadStateStore(createDataStore(newStoreFile(), backgroundScope))
        assertEquals(AccountNotifyReadState(), store.getState("10001"))
        assertEquals(AccountNotifyReadState(), store.observeState("10001").first())
    }

    @Test
    fun markRead_persistsAcrossInstancesAndIsolatesByOwnerUid() = runTest {
        val file = newStoreFile()
        val scope1 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store1 = NotifyReadStateStore(createDataStore(file, scope1))
            store1.syncLoadedFeed("10001", listOf("k1", "k2", "k3"))
            store1.markRead("10001", "k1")
            store1.syncLoadedFeed("10002", listOf("k1", "k4"))
            store1.markRead("10002", "k4")
        } finally {
            scope1.cancel()
            advanceUntilIdle()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store2 = NotifyReadStateStore(createDataStore(file, scope2))
            val stateA = store2.getState("10001")
            val stateB = store2.getState("10002")

            assertEquals(listOf("k1"), stateA.readKeys)
            assertEquals(listOf("k2", "k3"), stateA.unreadKeys)

            assertEquals(listOf("k4"), stateB.readKeys)
            assertEquals(listOf("k1"), stateB.unreadKeys)
        } finally {
            scope2.cancel()
            advanceUntilIdle()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun markRead_capsReadKeysAt500RetainingMostRecentAndMovesDuplicatesToEnd() = runTest {
        val store = NotifyReadStateStore(createDataStore(newStoreFile(), backgroundScope))
        val initialKeys = (1..520).map { "KEY:$it" }
        store.markAllRead(
            ownerUid = "10001",
            knownKeys = initialKeys,
            suppressedServerCount = 0,
            timestampMs = 1_000L,
        )

        val afterCap = store.getState("10001")
        assertEquals(NotifyReadStateStore.MAX_READ_KEYS, afterCap.readKeys.size)
        assertEquals("KEY:21", afterCap.readKeys.first())
        assertEquals("KEY:520", afterCap.readKeys.last())

        // Re-marking KEY:21 moves it to the most-recent tail so subsequent eviction drops KEY:22 instead.
        store.markRead("10001", "KEY:21")
        store.markRead("10001", "KEY:521")

        val afterRefresh = store.getState("10001")
        assertEquals(NotifyReadStateStore.MAX_READ_KEYS, afterRefresh.readKeys.size)
        assertTrue(afterRefresh.readKeys.contains("KEY:21"))
        assertFalse(afterRefresh.readKeys.contains("KEY:22"))
        assertEquals("KEY:521", afterRefresh.readKeys.last())
    }

    @Test
    fun syncLoadedFeed_computesUnreadAgainstReadKeysAndClearsSuppressedServerCount() = runTest {
        val store = NotifyReadStateStore(createDataStore(newStoreFile(), backgroundScope))
        store.markAllRead(
            ownerUid = "10001",
            knownKeys = listOf("k1", "k2"),
            suppressedServerCount = 5,
            timestampMs = 2_000L,
        )
        assertEquals(5, store.getState("10001").suppressedServerNotifyCount)

        val synced = store.syncLoadedFeed("10001", listOf("k1", "k2", "k3", "k3"))
        assertEquals(listOf("k1", "k2"), synced.readKeys)
        assertEquals(listOf("k3"), synced.unreadKeys)
        assertEquals(0, synced.suppressedServerNotifyCount)
    }

    @Test
    fun markAllRead_persistsKnownKeysWatermarkAndTimestampAndClearsUnread() = runTest {
        val store = NotifyReadStateStore(createDataStore(newStoreFile(), backgroundScope))
        store.syncLoadedFeed("10001", listOf("a", "b"))

        val marked = store.markAllRead(
            ownerUid = "10001",
            knownKeys = listOf("a", "b", "c"),
            suppressedServerCount = 4,
            timestampMs = 99_999L,
        )

        assertEquals(listOf("a", "b", "c"), marked.readKeys)
        assertTrue(marked.unreadKeys.isEmpty())
        assertEquals(4, marked.suppressedServerNotifyCount)
        assertEquals(99_999L, marked.markAllReadAtMs)
    }

    @Test
    fun corruptedProtoFileOnDisk_recoversToEmptyDefaultsWithoutThrowing() = runTest {
        val file = newStoreFile()
        file.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x7A, 0x13, 0x55))

        val store = NotifyReadStateStore(createDataStore(file, backgroundScope))
        assertEquals(AccountNotifyReadState(), store.getState("10001"))

        store.markRead("10001", "recovered_key")
        assertEquals(listOf("recovered_key"), store.getState("10001").readKeys)
    }

    @Test
    fun corruptedJsonAndWrongPreferenceTypes_fallbackToEmptyDefaultsWithoutAffectingOtherAccounts() = runTest {
        val dataStore = createDataStore(newStoreFile(), backgroundScope)
        val store = NotifyReadStateStore(dataStore)
        store.markRead("10003", "valid_key")

        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(NotifyReadStateStore.preferenceKeyName("10001"))] = "{not-valid-json"
            prefs[intPreferencesKey(NotifyReadStateStore.preferenceKeyName("10002"))] = 42
        }

        assertEquals(AccountNotifyReadState(), store.getState("10001"))
        assertEquals(AccountNotifyReadState(), store.getState("10002"))
        assertEquals(listOf("valid_key"), store.getState("10003").readKeys)
    }

    @Test
    fun ioExceptionFromDataStore_emitsEmptyDefaultsWithoutThrowing() = runTest {
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("Simulated disk read failure")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = emptyPreferences()
        }

        val store = NotifyReadStateStore(failingDataStore)
        assertEquals(AccountNotifyReadState(), store.getState("10001"))
        assertEquals(AccountNotifyReadState(), store.observeState("10001").first())
    }
}
