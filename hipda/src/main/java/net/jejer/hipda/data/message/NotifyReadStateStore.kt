package net.jejer.hipda.data.message

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.jejer.hipda.di.NotifyReadStateDataStore

/**
 * Per-account persisted read/unread notification state stored in the non-sensitive
 * `notify_read_state` Preferences DataStore.
 */
@Serializable
data class AccountNotifyReadState(
    /** Ordered recently-read notification keys (oldest first, newest last; capped at [NotifyReadStateStore.MAX_READ_KEYS]). */
    val readKeys: List<String> = emptyList(),
    /** Unopened notification keys from the last `notice.php` load (capped at [NotifyReadStateStore.MAX_UNREAD_KEYS]). */
    val unreadKeys: List<String> = emptyList(),
    /** Server prompt watermark suppressed by `markAllNotificationsRead()` before the next `notice.php` visit. */
    val suppressedServerNotifyCount: Int = 0,
    /** Epoch timestamp (ms) when `markAllNotificationsRead()` was last invoked. */
    val markAllReadAtMs: Long = 0L,
)

/**
 * Non-sensitive per-account local read-state persistence backed by `notify_read_state.preferences_pb`.
 */
@Singleton
class NotifyReadStateStore @Inject constructor(
    @NotifyReadStateDataStore
    private val dataStore: DataStore<Preferences>,
) {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun safePreferencesFlow(): Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    fun observeState(ownerUid: String): Flow<AccountNotifyReadState> {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return flowOf(AccountNotifyReadState())
        return safePreferencesFlow()
            .map { prefs -> decodeState(prefs, trimmedUid) }
            .distinctUntilChanged()
    }

    suspend fun getState(ownerUid: String): AccountNotifyReadState {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return AccountNotifyReadState()
        val prefs = safePreferencesFlow().first()
        return decodeState(prefs, trimmedUid)
    }

    suspend fun syncLoadedFeed(ownerUid: String, feedKeys: List<String>): AccountNotifyReadState {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return AccountNotifyReadState()
        var result = AccountNotifyReadState()
        dataStore.edit { prefs ->
            val current = decodeState(prefs, trimmedUid)
            val readSet = current.readKeys.toSet()
            val unread = feedKeys.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filterNot { it in readSet }
                .take(MAX_UNREAD_KEYS)
                .toList()
            result = current.copy(
                unreadKeys = unread,
                suppressedServerNotifyCount = 0,
            )
            prefs[stringPreferencesKey(preferenceKeyName(trimmedUid))] = json.encodeToString(result)
        }
        return result
    }

    suspend fun markRead(ownerUid: String, key: String): AccountNotifyReadState {
        val trimmedUid = ownerUid.trim()
        val trimmedKey = key.trim()
        if (trimmedUid.isEmpty()) return AccountNotifyReadState()
        if (trimmedKey.isEmpty()) return getState(trimmedUid)

        var result = AccountNotifyReadState()
        dataStore.edit { prefs ->
            val current = decodeState(prefs, trimmedUid)
            val updatedRead = deduplicateTail(current.readKeys + trimmedKey, MAX_READ_KEYS)
            val updatedUnread = current.unreadKeys.filterNot { it == trimmedKey }
            result = current.copy(
                readKeys = updatedRead,
                unreadKeys = updatedUnread,
            )
            prefs[stringPreferencesKey(preferenceKeyName(trimmedUid))] = json.encodeToString(result)
        }
        return result
    }

    suspend fun markAllRead(
        ownerUid: String,
        knownKeys: Collection<String>,
        suppressedServerCount: Int,
        timestampMs: Long,
    ): AccountNotifyReadState {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return AccountNotifyReadState()

        var result = AccountNotifyReadState()
        dataStore.edit { prefs ->
            val current = decodeState(prefs, trimmedUid)
            val combinedRead = deduplicateTail(
                keys = buildList(current.readKeys.size + current.unreadKeys.size + knownKeys.size) {
                    addAll(current.readKeys)
                    addAll(current.unreadKeys)
                    addAll(knownKeys)
                },
                maxSize = MAX_READ_KEYS,
            )
            result = current.copy(
                readKeys = combinedRead,
                unreadKeys = emptyList(),
                suppressedServerNotifyCount = suppressedServerCount.coerceAtLeast(0),
                markAllReadAtMs = timestampMs.coerceAtLeast(0L),
            )
            prefs[stringPreferencesKey(preferenceKeyName(trimmedUid))] = json.encodeToString(result)
        }
        return result
    }

    suspend fun clearSuppressedServerCount(ownerUid: String): AccountNotifyReadState {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return AccountNotifyReadState()

        var result = AccountNotifyReadState()
        dataStore.edit { prefs ->
            val current = decodeState(prefs, trimmedUid)
            if (current.suppressedServerNotifyCount == 0) {
                result = current
            } else {
                result = current.copy(suppressedServerNotifyCount = 0)
                prefs[stringPreferencesKey(preferenceKeyName(trimmedUid))] = json.encodeToString(result)
            }
        }
        return result
    }

    suspend fun clearAccount(ownerUid: String) {
        val trimmedUid = ownerUid.trim()
        if (trimmedUid.isEmpty()) return
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(preferenceKeyName(trimmedUid)))
        }
    }

    private fun decodeState(prefs: Preferences, trimmedUid: String): AccountNotifyReadState {
        val key = stringPreferencesKey(preferenceKeyName(trimmedUid))
        val raw = prefs.asMap()[key] as? String ?: return AccountNotifyReadState()
        val decoded = runCatching { json.decodeFromString<AccountNotifyReadState>(raw) }.getOrNull()
            ?: return AccountNotifyReadState()
        return normalizeState(decoded)
    }

    private fun normalizeState(state: AccountNotifyReadState): AccountNotifyReadState {
        val normalizedRead = deduplicateTail(state.readKeys, MAX_READ_KEYS)
        val readSet = normalizedRead.toSet()
        val normalizedUnread = state.unreadKeys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it in readSet }
            .take(MAX_UNREAD_KEYS)
            .toList()
        return AccountNotifyReadState(
            readKeys = normalizedRead,
            unreadKeys = normalizedUnread,
            suppressedServerNotifyCount = state.suppressedServerNotifyCount.coerceAtLeast(0),
            markAllReadAtMs = state.markAllReadAtMs.coerceAtLeast(0L),
        )
    }

    private fun deduplicateTail(keys: Collection<String>, maxSize: Int): List<String> {
        val ordered = LinkedHashSet<String>()
        for (raw in keys) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            ordered.remove(trimmed)
            ordered.add(trimmed)
        }
        val list = ordered.toList()
        return if (list.size > maxSize) list.takeLast(maxSize) else list
    }

    companion object {
        const val MAX_READ_KEYS: Int = 500
        const val MAX_UNREAD_KEYS: Int = 500

        internal fun preferenceKeyName(ownerUid: String): String = "account_${ownerUid.trim()}"
    }
}
