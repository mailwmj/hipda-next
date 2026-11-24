package net.jejer.hipda.data.settings

/**
 * Light/dark theme mode preference (product-design §5, §7.10).
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Reading body line spacing presets (product-design §5, §7.10).
 */
enum class LineSpacing(val multiplier: Float) {
    COMPACT(1.5f),
    STANDARD(1.65f),
    LOOSE(1.8f),
}

/**
 * Remembered list/detail split snap position per orientation (product-design §4.1).
 */
enum class PaneSplit {
    COLLAPSED,
    DEFAULT,
    BALANCED,
}

/**
 * Immutable snapshot of all user settings (product-design §7.10) and persisted pane split states
 * (product-design §4.1).
 */
data class AppSettings(
    // 外观与阅读
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val themeSeed: String = THEME_SEED_WALLPAPER,
    val fontStep: Int = DEFAULT_FONT_STEP,
    val lineSpacing: LineSpacing = LineSpacing.STANDARD,
    // 论坛与发帖
    val followedForums: List<Int> = DEFAULT_FOLLOWED_FORUMS,
    val showStickyThreads: Boolean = true,
    val sortByPostTime: Map<Int, Boolean> = emptyMap(),
    val tailEnabled: Boolean = false,
    val tailText: String = "",
    val tailUrl: String = "",
    // 通知与免打扰
    val notificationsEnabled: Boolean = true,
    val silentModeEnabled: Boolean = false,
    val silentBeginMinutes: Int = DEFAULT_SILENT_BEGIN_MINUTES,
    val silentEndMinutes: Int = DEFAULT_SILENT_END_MINUTES,
    // 网络与缓存
    val forumServer: String = DEFAULT_FORUM_SERVER,
    val imageCacheLimitMb: Int = DEFAULT_IMAGE_CACHE_LIMIT_MB,
    // 界面状态
    val paneSplitPortrait: PaneSplit = PaneSplit.DEFAULT,
    val paneSplitLandscape: PaneSplit = PaneSplit.DEFAULT,
) {
    fun isSortByPostTime(fid: Int): Boolean = sortByPostTime[fid] == true

    companion object {
        const val THEME_SEED_WALLPAPER: String = "WALLPAPER"

        const val MIN_FONT_STEP: Int = 0
        const val MAX_FONT_STEP: Int = 4
        const val DEFAULT_FONT_STEP: Int = 2

        /**
         * Legacy default followed forums: Discovery (2), Buy & Sell (6), Geek Talks (7).
         * Source: `HiUtils.java:81-82,110` (`DEFAULT_FORUMS`) and `HiSettingsHelper.java:671`.
         */
        val DEFAULT_FOLLOWED_FORUMS: List<Int> = listOf(2, 6, 7)

        const val MIN_MINUTES_OF_DAY: Int = 0
        const val MAX_MINUTES_OF_DAY: Int = 24 * 60 - 1

        /**
         * Default quiet hours window: 22:00 - 08:00.
         * Matches legacy `NotiHelper.java:49-50`.
         */
        const val DEFAULT_SILENT_BEGIN_MINUTES: Int = 22 * 60
        const val DEFAULT_SILENT_END_MINUTES: Int = 8 * 60

        /**
         * Legacy default forum server URL.
         * Source: `HiUtils.java:20` (`ForumServer`) and `HiSettingsHelper.java:1003`.
         */
        const val DEFAULT_FORUM_SERVER: String = "https://www.4d4y.com"

        /**
         * Legacy default image disk cache limit in MB.
         * Source: `MyGlideModule.java:46,52` (`DEFAULT_CACHE_SIZE`) and `pref_network.xml:29`.
         */
        const val DEFAULT_IMAGE_CACHE_LIMIT_MB: Int = 500
    }
}
