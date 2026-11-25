package net.jejer.hipda.feature.threaddetail.media

import java.util.Locale
import kotlin.math.roundToLong

/** 将图片文件字节数格式化为紧凑人类可读大小；`<= 0` 返回 `null`（调用方回退为不带括号的 `点击加载图片`）。 */
object ImageSizeFormatter {
    private const val KIB: Long = 1024L
    private const val MIB: Long = 1024L * 1024L
    private const val GIB: Long = 1024L * 1024L * 1024L

    fun formatOrNull(bytes: Long): String? {
        if (bytes <= 0L) return null
        if (bytes < KIB) return "$bytes B"
        val kb = bytes.toDouble() / KIB
        if (kb.roundToLong() < KIB) return formatUnit(kb, "KB")
        val mb = bytes.toDouble() / MIB
        if (mb.roundToLong() < KIB) return formatUnit(mb, "MB")
        return formatUnit(bytes.toDouble() / GIB, "GB")
    }

    private fun formatUnit(value: Double, unit: String): String {
        val tenths = (value * 10.0).roundToLong()
        return if (tenths >= 100L || tenths % 10L == 0L) {
            "${value.roundToLong()} $unit"
        } else {
            String.format(Locale.US, "%.1f %s", tenths / 10.0, unit)
        }
    }
}
