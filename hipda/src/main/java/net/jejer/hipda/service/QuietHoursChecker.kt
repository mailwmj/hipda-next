package net.jejer.hipda.service

/**
 * Pure quiet-hours evaluation supporting both same-day (`begin < end`) and cross-midnight
 * (`begin > end`, e.g. `22:00–08:00`) intervals using left-closed right-open `[begin, end)` bounds.
 */
object QuietHoursChecker {

    const val MINUTES_PER_DAY: Int = 24 * 60

    fun minutesOfDay(hour: Int, minute: Int): Int =
        Math.floorMod(hour * 60 + minute, MINUTES_PER_DAY)

    fun isInQuietHours(
        currentMinutesOfDay: Int,
        silentModeEnabled: Boolean,
        beginMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        if (!silentModeEnabled) return false
        val current = Math.floorMod(currentMinutesOfDay, MINUTES_PER_DAY)
        val begin = Math.floorMod(beginMinutes, MINUTES_PER_DAY)
        val end = Math.floorMod(endMinutes, MINUTES_PER_DAY)
        if (begin == end) return false
        return if (begin < end) {
            current in begin until end
        } else {
            current >= begin || current < end
        }
    }
}
