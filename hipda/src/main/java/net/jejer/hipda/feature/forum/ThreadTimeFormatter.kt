package net.jejer.hipda.feature.forum

import java.time.LocalDate
import java.util.Locale

private val DISCUZ_DATE_TIME_REGEX = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$""")

/** 论坛列表精简绝对时间（今日 `HH:mm`、昨日 [yesterdayLabel]、当年 `MM-dd`、跨年 `yyyy-MM-dd`；无法解析原文透传）。 */
fun formatThreadTime(raw: String, referenceDate: LocalDate, yesterdayLabel: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val m = DISCUZ_DATE_TIME_REGEX.matchEntire(trimmed) ?: return trimmed
    val date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return trimmed
    val timeText = if (m.groupValues[4].isNotEmpty() && m.groupValues[5].isNotEmpty()) {
        val hour = m.groupValues[4].toIntOrNull() ?: return trimmed
        val minute = m.groupValues[5].toIntOrNull() ?: return trimmed
        val secOk = m.groupValues[6].isEmpty() || (m.groupValues[6].toIntOrNull() in 0..59)
        if (hour !in 0..23 || minute !in 0..59 || !secOk) return trimmed
        String.format(Locale.US, "%02d:%02d", hour, minute)
    } else null
    return when {
        date == referenceDate -> timeText ?: trimmed
        date == referenceDate.minusDays(1) -> yesterdayLabel
        date.year == referenceDate.year -> String.format(Locale.US, "%02d-%02d", date.monthValue, date.dayOfMonth)
        else -> String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
    }
}
