package net.jejer.hipda.feature.forum

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadTimeFormatterTest {

    private val refDate = LocalDate.of(2026, 11, 28)

    @Test
    fun formatThreadTime_todayWithHourMinute_outputsZeroPaddedTime() {
        assertEquals("09:05", formatThreadTime("2026-11-28 09:05", refDate, "昨天"))
        assertEquals("08:05", formatThreadTime("2026-11-28 8:05", refDate, "昨天"))
        assertEquals("23:59", formatThreadTime("2026-11-28 23:59:12", refDate, "昨天"))
        assertEquals("09:05", formatThreadTime("  2026-11-28 09:05  ", refDate, "昨天"))
    }

    @Test
    fun formatThreadTime_todayWithoutTime_fallsBackToOriginal() {
        assertEquals("2026-11-28", formatThreadTime("2026-11-28", refDate, "昨天"))
    }

    @Test
    fun formatThreadTime_yesterday_outputsYesterdayLabelIncludingCrossYear() {
        assertEquals("昨天", formatThreadTime("2026-11-27 22:15", refDate, "昨天"))
        assertEquals("昨天", formatThreadTime("2026-11-27", refDate, "昨天"))
        assertEquals("昨天", formatThreadTime("2025-12-31 23:58", LocalDate.of(2026, 1, 1), "昨天"))
    }

    @Test
    fun formatThreadTime_sameYear_outputsZeroPaddedMonthDay() {
        assertEquals("06-03", formatThreadTime("2026-6-3 14:20", refDate, "昨天"))
        assertEquals("11-01", formatThreadTime("2026-11-01", refDate, "昨天"))
        assertEquals("01-09", formatThreadTime("2026-1-9 0:00", refDate, "昨天"))
    }

    @Test
    fun formatThreadTime_crossYear_outputsZeroPaddedYearMonthDay() {
        assertEquals("2022-06-13", formatThreadTime("2022-6-13 22:57", refDate, "昨天"))
        assertEquals("2025-12-31", formatThreadTime("2025-12-31 18:00", refDate, "昨天"))
        assertEquals("2021-01-05", formatThreadTime("2021-1-5", refDate, "昨天"))
    }

    @Test
    fun formatThreadTime_nonStandardOrInvalid_passesThroughUnchanged() {
        assertEquals("5分钟前", formatThreadTime("5分钟前", refDate, "昨天"))
        assertEquals("刚刚", formatThreadTime("刚刚", refDate, "昨天"))
        assertEquals("昨天", formatThreadTime("昨天", refDate, "昨天"))
        assertEquals("", formatThreadTime("", refDate, "昨天"))
        assertEquals("", formatThreadTime("   ", refDate, "昨天"))
        assertEquals("2026-02-30 12:00", formatThreadTime("2026-02-30 12:00", refDate, "昨天"))
        assertEquals("2026-11-28 25:00", formatThreadTime("2026-11-28 25:00", refDate, "昨天"))
    }
}
