package net.jejer.hipda.data.search

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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.jejer.hipda.di.SearchHistoryDataStore

internal object SearchHistoryPreferenceKeys {
    val QUERIES_JSON: Preferences.Key<String> = stringPreferencesKey("search_history_queries_json")
}

/**
 * Non-encrypted Preferences DataStore persistence for recent search queries (`search_history`,
 * merged from D3d).
 *
 * Keeps entries newest-first, deduplicates on insert, enforces a default cap of [DEFAULT_MAX_ITEMS],
 * supports exact-index Snackbar undo via [remove] + [restore], and safely recovers to an empty list
 * on file corruption or read [IOException].
 */
@Singleton
class SearchHistoryStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val maxItems: Int,
) {

    @Inject
    constructor(
        @SearchHistoryDataStore dataStore: DataStore<Preferences>,
    ) : this(
        dataStore = dataStore,
        maxItems = DEFAULT_MAX_ITEMS,
    )

    init {
        require(maxItems > 0) { "maxItems must be positive" }
    }

    /** Reactive list of recent search queries ordered newest-first. */
    val history: Flow<List<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::decodeHistory)
        .distinctUntilChanged()

    /** Reads the latest persisted search history snapshot once. */
    suspend fun read(): List<String> = history.first()

    /**
     * Records [query] at the front of the history list (trimmed, deduplicated, capped at [maxItems]).
     * Blank queries are ignored without mutating storage.
     */
    suspend fun record(query: String): List<String> {
        val normalized = normalizeQuery(query) ?: return read()
        var updatedList: List<String> = emptyList()
        dataStore.edit { prefs ->
            val current = decodeHistory(prefs)
            val next = buildList(minOf(current.size + 1, maxItems)) {
                add(normalized)
                for (existing in current) {
                    if (existing != normalized && size < maxItems) {
                        add(existing)
                    }
                }
            }
            updatedList = next
            prefs[SearchHistoryPreferenceKeys.QUERIES_JSON] = encodeHistory(next)
        }
        return updatedList
    }

    /**
     * Removes [query] from history and returns its original [RemovedSearchHistoryEntry] for Undo,
     * or `null` if [query] is blank or not present.
     */
    suspend fun remove(query: String): RemovedSearchHistoryEntry? {
        val normalized = normalizeQuery(query) ?: return null
        var removed: RemovedSearchHistoryEntry? = null
        dataStore.edit { prefs ->
            val current = decodeHistory(prefs)
            val index = current.indexOf(normalized)
            if (index >= 0) {
                removed = RemovedSearchHistoryEntry(query = normalized, index = index)
                val next = current.toMutableList().apply { removeAt(index) }
                if (next.isEmpty()) {
                    prefs.remove(SearchHistoryPreferenceKeys.QUERIES_JSON)
                } else {
                    prefs[SearchHistoryPreferenceKeys.QUERIES_JSON] = encodeHistory(next)
                }
            }
        }
        return removed
    }

    /**
     * Restores [query] to [index] (clamped to valid bounds, deduplicated, capped at [maxItems])
     * to support Snackbar Undo after [remove].
     */
    suspend fun restore(query: String, index: Int): List<String> {
        val normalized = normalizeQuery(query) ?: return read()
        var updatedList: List<String> = emptyList()
        dataStore.edit { prefs ->
            val mutable = decodeHistory(prefs)
                .filterNot { it == normalized }
                .toMutableList()
            val clampedIndex = index.coerceIn(0, mutable.size)
            mutable.add(clampedIndex, normalized)
            val next = if (mutable.size > maxItems) mutable.take(maxItems) else mutable.toList()
            updatedList = next
            prefs[SearchHistoryPreferenceKeys.QUERIES_JSON] = encodeHistory(next)
        }
        return updatedList
    }

    /** Convenience overload restoring a previously returned [RemovedSearchHistoryEntry]. */
    suspend fun restore(entry: RemovedSearchHistoryEntry): List<String> =
        restore(query = entry.query, index = entry.index)

    /** Clears all stored search history entries. */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(SearchHistoryPreferenceKeys.QUERIES_JSON)
        }
    }

    private fun normalizeQuery(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    private fun encodeHistory(queries: List<String>): String = buildJsonArray {
        queries.forEach { add(it) }
    }.toString()

    private fun decodeHistory(prefs: Preferences): List<String> {
        val raw = prefs.asMap()[SearchHistoryPreferenceKeys.QUERIES_JSON] as? String
            ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
            ?: return emptyList()

        val seen = LinkedHashSet<String>(minOf(array.size, maxItems))
        for (element in array) {
            if (seen.size >= maxItems) break
            val primitive = element as? JsonPrimitive ?: continue
            if (!primitive.isString) continue
            normalizeQuery(primitive.content)?.let(seen::add)
        }
        return seen.toList()
    }

    companion object {
        const val DEFAULT_MAX_ITEMS: Int = 20
    }
}
