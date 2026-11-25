package net.jejer.hipda.data.favorite

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
import net.jejer.hipda.di.FavoriteCacheDataStore

internal object FavoriteCachePreferenceKeys {
    val ACCOUNTS_JSON: Preferences.Key<String> = stringPreferencesKey("favorite_cache_accounts_json")
}

/**
 * Non-encrypted Preferences DataStore cache for per-account favorite and attention thread IDs
 * (`favorite_cache`, merged from D3d).
 *
 * Isolates each account's [FavoriteCacheSnapshot] by `ownerUid` so switching accounts or signing
 * out never leaks another account's favorites or attentions. Recovers safely to empty snapshots
 * on disk corruption or read [IOException].
 */
@Singleton
class FavoriteCacheStore @Inject constructor(
    @FavoriteCacheDataStore private val dataStore: DataStore<Preferences>,
) {

    private val allBuckets: Flow<Map<String, FavoriteCacheSnapshot>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::decodeBuckets)
        .distinctUntilChanged()

    /** Observes the persisted [FavoriteCacheSnapshot] for [ownerUid]. */
    fun observe(ownerUid: String): Flow<FavoriteCacheSnapshot> {
        val normalizedUid = normalizeUid(ownerUid) ?: return flowOf(FavoriteCacheSnapshot())
        return allBuckets
            .map { buckets -> buckets[normalizedUid] ?: FavoriteCacheSnapshot(ownerUid = normalizedUid) }
            .distinctUntilChanged()
    }

    /** Reads the latest persisted [FavoriteCacheSnapshot] for [ownerUid]. */
    suspend fun read(ownerUid: String): FavoriteCacheSnapshot = observe(ownerUid).first()

    /** Replaces all cached IDs for [kind] under [ownerUid] and updates its sync timestamp. */
    suspend fun replace(
        ownerUid: String,
        kind: FavoriteKind,
        tids: Collection<String>,
        syncedAtMs: Long,
    ): FavoriteCacheSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedSet = normalizeTidSet(tids)
        val clampedTs = syncedAtMs.coerceAtLeast(0L)
        return updateAccountBucket(normalizedUid) { existing ->
            when (kind) {
                FavoriteKind.FAVORITE -> existing.copy(
                    ownerUid = normalizedUid,
                    favoriteTids = normalizedSet,
                    lastFavoriteSyncedAtMs = clampedTs,
                )

                FavoriteKind.ATTENTION -> existing.copy(
                    ownerUid = normalizedUid,
                    attentionTids = normalizedSet,
                    lastAttentionSyncedAtMs = clampedTs,
                )
            }
        }
    }

    /** Merges valid [tids] into the existing set for [kind] under [ownerUid]. */
    suspend fun merge(
        ownerUid: String,
        kind: FavoriteKind,
        tids: Collection<String>,
    ): FavoriteCacheSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val additions = normalizeTidSet(tids)
        if (additions.isEmpty()) return read(normalizedUid)
        return updateAccountBucket(normalizedUid) { existing ->
            when (kind) {
                FavoriteKind.FAVORITE -> existing.copy(
                    ownerUid = normalizedUid,
                    favoriteTids = normalizeTidSet(existing.favoriteTids + additions),
                )

                FavoriteKind.ATTENTION -> existing.copy(
                    ownerUid = normalizedUid,
                    attentionTids = normalizeTidSet(existing.attentionTids + additions),
                )
            }
        }
    }

    /** Adds a single valid [tid] to [kind] under [ownerUid]. */
    suspend fun add(
        ownerUid: String,
        kind: FavoriteKind,
        tid: String,
    ): FavoriteCacheSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedTid = requireNotNull(normalizeFavoriteTid(tid)) { "tid must be a positive numeric ID" }
        return updateAccountBucket(normalizedUid) { existing ->
            when (kind) {
                FavoriteKind.FAVORITE -> existing.copy(
                    ownerUid = normalizedUid,
                    favoriteTids = normalizeTidSet(existing.favoriteTids + normalizedTid),
                )

                FavoriteKind.ATTENTION -> existing.copy(
                    ownerUid = normalizedUid,
                    attentionTids = normalizeTidSet(existing.attentionTids + normalizedTid),
                )
            }
        }
    }

    /** Removes a single [tid] from [kind] under [ownerUid]. */
    suspend fun remove(
        ownerUid: String,
        kind: FavoriteKind,
        tid: String,
    ): FavoriteCacheSnapshot {
        val normalizedUid = requireNotNull(normalizeUid(ownerUid)) { "ownerUid must not be blank" }
        val normalizedTid = requireNotNull(normalizeFavoriteTid(tid)) { "tid must be a positive numeric ID" }
        return updateAccountBucket(normalizedUid) { existing ->
            when (kind) {
                FavoriteKind.FAVORITE -> existing.copy(
                    ownerUid = normalizedUid,
                    favoriteTids = normalizeTidSet(existing.favoriteTids - normalizedTid),
                )

                FavoriteKind.ATTENTION -> existing.copy(
                    ownerUid = normalizedUid,
                    attentionTids = normalizeTidSet(existing.attentionTids - normalizedTid),
                )
            }
        }
    }

    /** Clears all cached favorite/attention IDs for [ownerUid] without touching other accounts. */
    suspend fun clear(ownerUid: String) {
        val normalizedUid = normalizeUid(ownerUid) ?: return
        dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            if (updated.remove(normalizedUid) != null) {
                if (updated.isEmpty()) {
                    prefs.remove(FavoriteCachePreferenceKeys.ACCOUNTS_JSON)
                } else {
                    prefs[FavoriteCachePreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
                }
            }
        }
    }

    /** Clears all cached favorite/attention buckets for all accounts. */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(FavoriteCachePreferenceKeys.ACCOUNTS_JSON)
        }
    }

    private suspend fun updateAccountBucket(
        normalizedUid: String,
        transform: (FavoriteCacheSnapshot) -> FavoriteCacheSnapshot,
    ): FavoriteCacheSnapshot {
        val updatedPrefs = dataStore.edit { prefs ->
            val updated = decodeBuckets(prefs).toMutableMap()
            val existing = updated[normalizedUid] ?: FavoriteCacheSnapshot(ownerUid = normalizedUid)
            updated[normalizedUid] = transform(existing)
            prefs[FavoriteCachePreferenceKeys.ACCOUNTS_JSON] = encodeBuckets(updated)
        }
        return decodeBuckets(updatedPrefs)[normalizedUid]
            ?: FavoriteCacheSnapshot(ownerUid = normalizedUid)
    }

    private fun normalizeUid(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    private fun normalizeTidSet(rawTids: Collection<String>): Set<String> =
        rawTids.mapNotNull(::normalizeFavoriteTid)
            .sorted()
            .toCollection(LinkedHashSet())
            .toSet()

    private fun encodeBuckets(buckets: Map<String, FavoriteCacheSnapshot>): String = buildJsonObject {
        buckets.toSortedMap().forEach { (uid, snapshot) ->
            put(
                uid,
                buildJsonObject {
                    put(
                        KEY_FAVORITE_TIDS,
                        buildJsonArray { snapshot.favoriteTids.sorted().forEach { add(it) } },
                    )
                    put(
                        KEY_ATTENTION_TIDS,
                        buildJsonArray { snapshot.attentionTids.sorted().forEach { add(it) } },
                    )
                    snapshot.lastFavoriteSyncedAtMs?.let { put(KEY_LAST_FAVORITE_SYNCED_AT_MS, it) }
                    snapshot.lastAttentionSyncedAtMs?.let { put(KEY_LAST_ATTENTION_SYNCED_AT_MS, it) }
                },
            )
        }
    }.toString()

    private fun decodeBuckets(prefs: Preferences): Map<String, FavoriteCacheSnapshot> {
        val raw = prefs.asMap()[FavoriteCachePreferenceKeys.ACCOUNTS_JSON] as? String
            ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyMap()

        val result = LinkedHashMap<String, FavoriteCacheSnapshot>(root.size)
        for ((uidKey, element) in root) {
            val uid = normalizeUid(uidKey) ?: continue
            val obj = element as? JsonObject ?: continue
            val favTids = decodeTidArray(obj[KEY_FAVORITE_TIDS]) ?: continue
            val attTids = decodeTidArray(obj[KEY_ATTENTION_TIDS]) ?: continue

            result[uid] = FavoriteCacheSnapshot(
                ownerUid = uid,
                favoriteTids = favTids,
                attentionTids = attTids,
                lastFavoriteSyncedAtMs = decodeOptionalTimestamp(obj[KEY_LAST_FAVORITE_SYNCED_AT_MS]),
                lastAttentionSyncedAtMs = decodeOptionalTimestamp(obj[KEY_LAST_ATTENTION_SYNCED_AT_MS]),
            )
        }
        return result
    }

    private fun decodeTidArray(element: kotlinx.serialization.json.JsonElement?): Set<String>? {
        val array = element as? JsonArray ?: return null
        val tids = LinkedHashSet<String>(array.size)
        for (item in array) {
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) return null
            normalizeFavoriteTid(primitive.content)?.let(tids::add)
        }
        return tids.sorted().toCollection(LinkedHashSet()).toSet()
    }

    private fun decodeOptionalTimestamp(element: kotlinx.serialization.json.JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull?.takeIf { it >= 0L }
    }

    private companion object {
        private const val KEY_FAVORITE_TIDS = "favoriteTids"
        private const val KEY_ATTENTION_TIDS = "attentionTids"
        private const val KEY_LAST_FAVORITE_SYNCED_AT_MS = "lastFavoriteSyncedAtMs"
        private const val KEY_LAST_ATTENTION_SYNCED_AT_MS = "lastAttentionSyncedAtMs"
    }
}
