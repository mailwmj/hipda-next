package net.jejer.hipda.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * Reactive store for [AppSettings] backed by Preferences DataStore.
 */
interface SettingsRepository {

    /**
     * Cold [Flow] emitting the current [AppSettings] whenever any setting changes.
     * Unrecognized enum names or corrupted preference values safely fall back to defaults.
     */
    val settings: Flow<AppSettings>

    // 外观与阅读
    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setPureBlack(enabled: Boolean)
    suspend fun setThemeSeed(themeSeed: String)
    suspend fun setFontStep(step: Int)
    suspend fun setLineSpacing(lineSpacing: LineSpacing)

    // 论坛与发帖
    suspend fun setFollowedForums(forumIds: List<Int>)
    suspend fun setShowStickyThreads(show: Boolean)
    suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean)
    suspend fun setTailEnabled(enabled: Boolean)
    suspend fun setTailText(text: String)
    suspend fun setTailUrl(url: String)

    // 通知与免打扰
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setSilentModeEnabled(enabled: Boolean)
    suspend fun setSilentBeginMinutes(minutes: Int)
    suspend fun setSilentEndMinutes(minutes: Int)

    // 网络与缓存
    suspend fun setForumServer(server: String)
    suspend fun setImageCacheLimitMb(limitMb: Int)

    // 界面状态
    suspend fun setPaneSplitPortrait(split: PaneSplit)
    suspend fun setPaneSplitLandscape(split: PaneSplit)
    suspend fun setSidebarCollapsedPortrait(collapsed: Boolean)
    suspend fun setSidebarCollapsedLandscape(collapsed: Boolean)
}
