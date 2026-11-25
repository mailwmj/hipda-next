package net.jejer.hipda.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal object SettingsPreferenceKeys {
    // 外观与阅读
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val PURE_BLACK = booleanPreferencesKey("pure_black")
    val THEME_SEED = stringPreferencesKey("theme_seed")
    val FONT_STEP = intPreferencesKey("font_step")
    val LINE_SPACING = stringPreferencesKey("line_spacing")

    // 论坛与发帖
    val FOLLOWED_FORUMS = stringPreferencesKey("followed_forums")
    val SHOW_STICKY_THREADS = booleanPreferencesKey("show_sticky_threads")
    val SORT_BY_POST_TIME = stringPreferencesKey("sort_by_post_time")
    val TAIL_ENABLED = booleanPreferencesKey("tail_enabled")
    val TAIL_TEXT = stringPreferencesKey("tail_text")
    val TAIL_URL = stringPreferencesKey("tail_url")

    // 通知与免打扰
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val SILENT_MODE_ENABLED = booleanPreferencesKey("silent_mode_enabled")
    val SILENT_BEGIN_MINUTES = intPreferencesKey("silent_begin_minutes")
    val SILENT_END_MINUTES = intPreferencesKey("silent_end_minutes")

    // 网络与缓存
    val FORUM_SERVER = stringPreferencesKey("forum_server")
    val IMAGE_CACHE_LIMIT_MB = intPreferencesKey("image_cache_limit_mb")

    // 界面状态
    val PANE_SPLIT_PORTRAIT = stringPreferencesKey("pane_split_portrait")
    val PANE_SPLIT_LANDSCAPE = stringPreferencesKey("pane_split_landscape")
    val SIDEBAR_COLLAPSED_PORTRAIT = booleanPreferencesKey("sidebar_collapsed_portrait")
    val SIDEBAR_COLLAPSED_LANDSCAPE = booleanPreferencesKey("sidebar_collapsed_landscape")
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::mapPreferences)
        .distinctUntilChanged()

    override suspend fun setThemeMode(themeMode: ThemeMode) =
        setPreference(SettingsPreferenceKeys.THEME_MODE, themeMode.name)

    override suspend fun setPureBlack(enabled: Boolean) =
        setPreference(SettingsPreferenceKeys.PURE_BLACK, enabled)

    override suspend fun setThemeSeed(themeSeed: String) =
        setPreference(SettingsPreferenceKeys.THEME_SEED, sanitizeThemeSeed(themeSeed))

    override suspend fun setFontStep(step: Int) =
        setPreference(SettingsPreferenceKeys.FONT_STEP, clampFontStep(step))

    override suspend fun setLineSpacing(lineSpacing: LineSpacing) =
        setPreference(SettingsPreferenceKeys.LINE_SPACING, lineSpacing.name)

    override suspend fun setFollowedForums(forumIds: List<Int>) =
        setPreference(SettingsPreferenceKeys.FOLLOWED_FORUMS, encodeFollowedForums(forumIds))

    override suspend fun setShowStickyThreads(show: Boolean) =
        setPreference(SettingsPreferenceKeys.SHOW_STICKY_THREADS, show)

    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) {
        dataStore.edit { prefs ->
            val updated = decodeSortByPostTime(
                prefs.safeString(SettingsPreferenceKeys.SORT_BY_POST_TIME),
            ).toMutableMap()
            updated[fid] = sortByPostTime
            prefs[SettingsPreferenceKeys.SORT_BY_POST_TIME] = encodeSortByPostTime(updated)
        }
    }

    override suspend fun setTailEnabled(enabled: Boolean) =
        setPreference(SettingsPreferenceKeys.TAIL_ENABLED, enabled)

    override suspend fun setTailText(text: String) =
        setPreference(SettingsPreferenceKeys.TAIL_TEXT, text)

    override suspend fun setTailUrl(url: String) =
        setPreference(SettingsPreferenceKeys.TAIL_URL, url)

    override suspend fun setNotificationsEnabled(enabled: Boolean) =
        setPreference(SettingsPreferenceKeys.NOTIFICATIONS_ENABLED, enabled)

    override suspend fun setSilentModeEnabled(enabled: Boolean) =
        setPreference(SettingsPreferenceKeys.SILENT_MODE_ENABLED, enabled)

    override suspend fun setSilentBeginMinutes(minutes: Int) =
        setPreference(SettingsPreferenceKeys.SILENT_BEGIN_MINUTES, clampMinutesOfDay(minutes))

    override suspend fun setSilentEndMinutes(minutes: Int) =
        setPreference(SettingsPreferenceKeys.SILENT_END_MINUTES, clampMinutesOfDay(minutes))

    override suspend fun setForumServer(server: String) =
        setPreference(SettingsPreferenceKeys.FORUM_SERVER, sanitizeForumServer(server))

    override suspend fun setImageCacheLimitMb(limitMb: Int) =
        setPreference(SettingsPreferenceKeys.IMAGE_CACHE_LIMIT_MB, sanitizeImageCacheLimitMb(limitMb))

    override suspend fun setPaneSplitPortrait(split: PaneSplit) =
        setPreference(SettingsPreferenceKeys.PANE_SPLIT_PORTRAIT, split.name)

    override suspend fun setPaneSplitLandscape(split: PaneSplit) =
        setPreference(SettingsPreferenceKeys.PANE_SPLIT_LANDSCAPE, split.name)

    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) =
        setPreference(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_PORTRAIT, collapsed)

    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) =
        setPreference(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_LANDSCAPE, collapsed)

    private suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    private fun mapPreferences(prefs: Preferences): AppSettings = AppSettings(
        themeMode = parseEnumOrDefault(
            raw = prefs.safeString(SettingsPreferenceKeys.THEME_MODE),
            entries = ThemeMode.entries,
            default = ThemeMode.SYSTEM,
        ),
        pureBlack = prefs.safeBoolean(SettingsPreferenceKeys.PURE_BLACK) ?: false,
        themeSeed = sanitizeThemeSeed(prefs.safeString(SettingsPreferenceKeys.THEME_SEED)),
        fontStep = clampFontStep(
            prefs.safeInt(SettingsPreferenceKeys.FONT_STEP) ?: AppSettings.DEFAULT_FONT_STEP,
        ),
        lineSpacing = parseEnumOrDefault(
            raw = prefs.safeString(SettingsPreferenceKeys.LINE_SPACING),
            entries = LineSpacing.entries,
            default = LineSpacing.STANDARD,
        ),
        followedForums = decodeFollowedForums(
            prefs.safeString(SettingsPreferenceKeys.FOLLOWED_FORUMS),
        ),
        showStickyThreads = prefs.safeBoolean(SettingsPreferenceKeys.SHOW_STICKY_THREADS) ?: true,
        sortByPostTime = decodeSortByPostTime(
            prefs.safeString(SettingsPreferenceKeys.SORT_BY_POST_TIME),
        ),
        tailEnabled = prefs.safeBoolean(SettingsPreferenceKeys.TAIL_ENABLED) ?: false,
        tailText = prefs.safeString(SettingsPreferenceKeys.TAIL_TEXT) ?: "",
        tailUrl = prefs.safeString(SettingsPreferenceKeys.TAIL_URL) ?: "",
        notificationsEnabled =
            prefs.safeBoolean(SettingsPreferenceKeys.NOTIFICATIONS_ENABLED) ?: true,
        silentModeEnabled = prefs.safeBoolean(SettingsPreferenceKeys.SILENT_MODE_ENABLED) ?: false,
        silentBeginMinutes = readMinutesOrDefault(
            value = prefs.safeInt(SettingsPreferenceKeys.SILENT_BEGIN_MINUTES),
            default = AppSettings.DEFAULT_SILENT_BEGIN_MINUTES,
        ),
        silentEndMinutes = readMinutesOrDefault(
            value = prefs.safeInt(SettingsPreferenceKeys.SILENT_END_MINUTES),
            default = AppSettings.DEFAULT_SILENT_END_MINUTES,
        ),
        forumServer = sanitizeForumServer(prefs.safeString(SettingsPreferenceKeys.FORUM_SERVER)),
        imageCacheLimitMb = sanitizeImageCacheLimitMb(
            prefs.safeInt(SettingsPreferenceKeys.IMAGE_CACHE_LIMIT_MB),
        ),
        paneSplitPortrait = parseEnumOrDefault(
            raw = prefs.safeString(SettingsPreferenceKeys.PANE_SPLIT_PORTRAIT),
            entries = PaneSplit.entries,
            default = PaneSplit.DEFAULT,
        ),
        paneSplitLandscape = parseEnumOrDefault(
            raw = prefs.safeString(SettingsPreferenceKeys.PANE_SPLIT_LANDSCAPE),
            entries = PaneSplit.entries,
            default = PaneSplit.DEFAULT,
        ),
        sidebarCollapsedPortrait =
            prefs.safeBoolean(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_PORTRAIT) ?: false,
        sidebarCollapsedLandscape =
            prefs.safeBoolean(SettingsPreferenceKeys.SIDEBAR_COLLAPSED_LANDSCAPE) ?: false,
    )

    private fun Preferences.safeInt(key: Preferences.Key<Int>): Int? = asMap()[key] as? Int

    private fun Preferences.safeBoolean(key: Preferences.Key<Boolean>): Boolean? =
        asMap()[key] as? Boolean

    private fun Preferences.safeString(key: Preferences.Key<String>): String? =
        asMap()[key] as? String

    private fun <T : Enum<T>> parseEnumOrDefault(
        raw: String?,
        entries: List<T>,
        default: T,
    ): T = if (raw == null) default else entries.firstOrNull { it.name == raw } ?: default

    private fun clampFontStep(step: Int): Int =
        step.coerceIn(AppSettings.MIN_FONT_STEP, AppSettings.MAX_FONT_STEP)

    private fun clampMinutesOfDay(minutes: Int): Int =
        minutes.coerceIn(AppSettings.MIN_MINUTES_OF_DAY, AppSettings.MAX_MINUTES_OF_DAY)

    private fun readMinutesOrDefault(value: Int?, default: Int): Int =
        if (value != null && value in AppSettings.MIN_MINUTES_OF_DAY..AppSettings.MAX_MINUTES_OF_DAY) {
            value
        } else {
            default
        }

    private fun sanitizeThemeSeed(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: AppSettings.THEME_SEED_WALLPAPER

    private fun sanitizeForumServer(raw: String?): String =
        raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: AppSettings.DEFAULT_FORUM_SERVER

    private fun sanitizeImageCacheLimitMb(limitMb: Int?): Int =
        if (limitMb != null && limitMb > 0) limitMb else AppSettings.DEFAULT_IMAGE_CACHE_LIMIT_MB

    private fun encodeFollowedForums(forumIds: List<Int>): String = buildJsonArray {
        forumIds.filter { it > 0 }.distinct().forEach { add(it) }
    }.toString()

    private fun decodeFollowedForums(raw: String?): List<Int> {
        if (raw == null) return AppSettings.DEFAULT_FOLLOWED_FORUMS
        val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
            ?: return AppSettings.DEFAULT_FOLLOWED_FORUMS
        val result = LinkedHashSet<Int>(array.size)
        for (element in array) {
            val primitive = element as? JsonPrimitive ?: return AppSettings.DEFAULT_FOLLOWED_FORUMS
            if (primitive.isString) return AppSettings.DEFAULT_FOLLOWED_FORUMS
            val fid = primitive.intOrNull ?: return AppSettings.DEFAULT_FOLLOWED_FORUMS
            if (fid <= 0) return AppSettings.DEFAULT_FOLLOWED_FORUMS
            result.add(fid)
        }
        return result.toList()
    }

    private fun encodeSortByPostTime(map: Map<Int, Boolean>): String = buildJsonObject {
        map.toSortedMap().forEach { (fid, value) ->
            put(fid.toString(), value)
        }
    }.toString()

    private fun decodeSortByPostTime(raw: String?): Map<Int, Boolean> {
        if (raw == null) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val result = LinkedHashMap<Int, Boolean>(obj.size)
        for ((key, element) in obj) {
            val fid = key.toIntOrNull() ?: return emptyMap()
            val primitive = element as? JsonPrimitive ?: return emptyMap()
            if (primitive.isString) return emptyMap()
            val value = primitive.booleanOrNull ?: return emptyMap()
            result[fid] = value
        }
        return result
    }
}
