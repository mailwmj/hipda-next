package net.jejer.hipda.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursCheckerTest {

    @Test
    fun disabledSilentMode_alwaysReturnsFalseAcrossSameDayAndCrossMidnight() {
        val sampleMinutes = listOf(0, 60, 479, 480, 780, 869, 1319, 1320, 1439)
        for (minute in sampleMinutes) {
            assertFalse(
                "Cross-midnight window must return false when disabled at minute=$minute",
                QuietHoursChecker.isInQuietHours(
                    currentMinutesOfDay = minute,
                    silentModeEnabled = false,
                    beginMinutes = 22 * 60,
                    endMinutes = 8 * 60,
                ),
            )
            assertFalse(
                "Same-day window must return false when disabled at minute=$minute",
                QuietHoursChecker.isInQuietHours(
                    currentMinutesOfDay = minute,
                    silentModeEnabled = false,
                    beginMinutes = 13 * 60,
                    endMinutes = 14 * 60 + 30,
                ),
            )
        }
    }

    @Test
    fun equalBeginAndEnd_treatedAsEmptyWindowNeverSilencingWholeDay() {
        val equalBounds = listOf(0, 8 * 60, 13 * 60 + 30, 22 * 60, 23 * 60 + 59)
        val probeMinutes = listOf(0, 8 * 60, 13 * 60 + 30, 22 * 60, 23 * 60 + 59)
        for (bound in equalBounds) {
            for (current in probeMinutes) {
                assertFalse(
                    "begin==end ($bound) must never be quiet at current=$current",
                    QuietHoursChecker.isInQuietHours(
                        currentMinutesOfDay = current,
                        silentModeEnabled = true,
                        beginMinutes = bound,
                        endMinutes = bound,
                    ),
                )
            }
        }
    }

    @Test
    fun sameDayWindow_usesLeftClosedRightOpenBounds() {
        val begin = QuietHoursChecker.minutesOfDay(13, 0) // 780
        val end = QuietHoursChecker.minutesOfDay(14, 30) // 870

        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(0, 0), true, begin, end))
        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(12, 59), true, begin, end))
        assertTrue(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(13, 0), true, begin, end))
        assertTrue(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(13, 45), true, begin, end))
        assertTrue(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(14, 29), true, begin, end))
        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(14, 30), true, begin, end))
        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(23, 59), true, begin, end))
    }

    @Test
    fun crossMidnightDefaultWindow_2200To0800_handlesEveryCriticalBoundaryMinute() {
        val begin = QuietHoursChecker.minutesOfDay(22, 0) // 1320
        val end = QuietHoursChecker.minutesOfDay(8, 0) // 480

        assertFalse("21:59 must NOT be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(21, 59), true, begin, end))
        assertTrue("22:00 must be quiet (left-closed)", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(22, 0), true, begin, end))
        assertTrue("23:59 must be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(23, 59), true, begin, end))
        assertTrue("00:00 must be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(0, 0), true, begin, end))
        assertTrue("03:30 must be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(3, 30), true, begin, end))
        assertTrue("07:59 must be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(7, 59), true, begin, end))
        assertFalse("08:00 must NOT be quiet (right-open)", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(8, 0), true, begin, end))
        assertFalse("12:00 must NOT be quiet", QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(12, 0), true, begin, end))
    }

    @Test
    fun extremeWindowsAndModuloNormalization_behaveDeterministically() {
        // Cross-midnight 1-minute window: [23:59, 00:00)
        val beginLate = QuietHoursChecker.minutesOfDay(23, 59)
        val endMidnight = QuietHoursChecker.minutesOfDay(0, 0)
        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(23, 58), true, beginLate, endMidnight))
        assertTrue(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(23, 59), true, beginLate, endMidnight))
        assertFalse(QuietHoursChecker.isInQuietHours(QuietHoursChecker.minutesOfDay(0, 0), true, beginLate, endMidnight))

        // Out-of-range inputs wrap cleanly modulo 1440 (1440 -> 00:00, -1 -> 23:59)
        assertEquals(0, QuietHoursChecker.minutesOfDay(24, 0))
        assertEquals(1439, QuietHoursChecker.minutesOfDay(0, -1))
        assertTrue(QuietHoursChecker.isInQuietHours(1440, true, 22 * 60, 8 * 60))
        assertTrue(QuietHoursChecker.isInQuietHours(-1, true, 22 * 60, 8 * 60))
    }
}
