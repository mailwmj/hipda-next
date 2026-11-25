package net.jejer.hipda.data.user

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.jejer.hipda.di.BlacklistDataStore

internal object BlacklistPreferenceKeys {
    val ACCOUNTS_JSON: Preferences.Key<String> = stringPreferencesKey("blacklist_accounts_json")
}

/**
 * Non-encrypted Preferences DataStore cache for per-account blacklists (`blacklist_cache`).
 *
 * Isolates each account's usernames and last sync timestamp by `ownerUid` so switching accounts
 * or signing out never leaks another account's blocked list. Recovers safely to empty snapshots
 * on disk corruption or read [IOException].
 */
@Singleton
class BlacklistStore @Inject constructor(
    @BlacklistDataStore private val dataStore: DataStore<Preferences>,
) {

    private val allBuckets: Flow<Map<String, BlacklistSnapshot>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::decodeBuckets)
        .distinctUntilChanged()

    fun observe(ownerUid: String): Flow<BlacklistSnapshot> {
        val normalizedUid = normalizeUid(ownerUid) ?: return flowOf(BlacklistSnapshot())
        return allBuckets
            .map { buckets -> buckets[normalizedUid] ?: BlacklistSnapshot(ownerUid = normalizedUid) }
            .distinctUntilChanged()
    }

    suspend fun read(ownerUid: String): BlacklistSnapshot = observe(ownerUid).first()

    suspend fun replace(
        ownerUid: String,
        usernames: Collection<String>,
        syncedAtMs: Long,
    ): BlacklistSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedSet = normalizeUsernameSet(usernames)
        val snapshot = BlacklistSnapshot(
            ownerUid = normalizedUid,
            usernames = normalizedSet,
            lastSyncedAtMs = syncedAtMs.coerceAtLeast(0L),
        )
        dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            updated[normalizedUid] = snapshot
            prefs[BlacklistPreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
        }
        return snapshot
    }

    suspend fun add(ownerUid: String, username: String): BlacklistSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedUser = requireNotNull(normalizeBlacklistUsername(username)) {
            "username must not be blank"
        }
        val updatedPrefs = dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            val existing = updated[normalizedUid] ?: BlacklistSnapshot(ownerUid = normalizedUid)
            val nextSet = normalizeUsernameSet(existing.usernames + normalizedUser)
            updated[normalizedUid] = existing.copy(ownerUid = normalizedUid, usernames = nextSet)
            prefs[BlacklistPreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
        }
        return decodeBuckets(updatedPrefs)[normalizedUid]
            ?: BlacklistSnapshot(ownerUid = normalizedUid)
    }

    suspend fun remove(ownerUid: String, username: String): BlacklistSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedUser = requireNotNull(normalizeBlacklistUsername(username)) {
            "username must not be blank"
        }
        val updatedPrefs = dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            val existing = updated[normalizedUid] ?: BlacklistSnapshot(ownerUid = normalizedUid)
            val nextSet = normalizeUsernameSet(existing.usernames - normalizedUser)
            updated[normalizedUid] = existing.copy(ownerUid = normalizedUid, usernames = nextSet)
            prefs[BlacklistPreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
        }
        return decodeBuckets(updatedPrefs)[normalizedUid]
            ?: BlacklistSnapshot(ownerUid = normalizedUid)
    }

    suspend fun clear(ownerUid: String) {
        val normalizedUid = normalizeUid(ownerUid) ?: return
        dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            if (updated.remove(normalizedUid) != null) {
                if (updated.isEmpty()) {
                    prefs.remove(BlacklistPreferenceKeys.ACCOUNTS_JSON)
                } else {
                    prefs[BlacklistPreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
                }
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(BlacklistPreferenceKeys.ACCOUNTS_JSON)
        }
    }

    private fun normalizeUid(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    private fun normalizeUsernameSet(rawUsernames: Collection<String>): Set<String> =
        rawUsernames.mapNotNull(::normalizeBlacklistUsername)
            .sorted()
            .toCollection(LinkedHashSet())
            .toSet()

    private fun encodeBuckets(buckets: Map<String, BlacklistSnapshot>): String = buildJsonObject {
        buckets.toSortedMap().forEach { (uid, snapshot) ->
            put(
                uid,
                buildJsonObject {
                    put(
                        KEY_USERNAMES,
                        buildJsonArray {
                            snapshot.usernames.sorted().forEach { add(it) }
                        },
                    )
                    snapshot.lastSyncedAtMs?.let { syncedAt ->
                        put(KEY_LAST_SYNCED_AT_MS, syncedAt)
                    }
                },
            )
        }
    }.toString()

    private fun decodeBuckets(prefs: Preferences): Map<String, BlacklistSnapshot> {
        val raw = prefs.asMap()[BlacklistPreferenceKeys.ACCOUNTS_JSON] as? String
            ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyMap()

        val result = LinkedHashMap<String, BlacklistSnapshot>(root.size)
        for ((uidKey, element) in root) {
            val uid = normalizeUid(uidKey) ?: continue
            val obj = element as? JsonObject ?: continue
            val usernamesArray = obj[KEY_USERNAMES] as? JsonArray ?: continue

            val usernames = LinkedHashSet<String>(usernamesArray.size)
            var validUsernames = true
            for (item in usernamesArray) {
                val primitive = item as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    validUsernames = false
                    break
                }
                normalizeBlacklistUsername(primitive.content)?.let(usernames::add)
            }
            if (!validUsernames) continue

            val lastSyncedAtMs = when (val tsElement = obj[KEY_LAST_SYNCED_AT_MS]) {
                null -> null
                is JsonPrimitive -> if (!tsElement.isString) {
                    tsElement.longOrNull?.takeIf { it >= 0L }
                } else {
                    null
                }
                else -> null
            }

            result[uid] = BlacklistSnapshot(
                ownerUid = uid,
                usernames = usernames.sorted().toCollection(LinkedHashSet()).toSet(),
                lastSyncedAtMs = lastSyncedAtMs,
            )
        }
        return result
    }

    private companion object {
        private const val KEY_USERNAMES = "usernames"
        private const val KEY_LAST_SYNCED_AT_MS = "lastSyncedAtMs"
    }
}
