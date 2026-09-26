package com.yuzhiplant.aiquota.data

import java.text.DecimalFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
    private val numberFmt = DecimalFormat("#,##0.##")

    fun percent(p: Double): String = String.format(Locale.US, "%.0f%%", p)

    fun usd(v: Double): String = String.format(Locale.US, "$%,.2f", v)

    fun number(v: Double): String = numberFmt.format(v)

    fun bytes(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.US, "%.2f GB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.0f MB", v / 1e6)
        else -> String.format(Locale.US, "%.0f KB", v / 1e3)
    }

    fun updatedAt(epochMs: Long): String {
        if (epochMs <= 0) return "尚未更新"
        return timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }

    /** ISO 時間字串 → epoch ms；解析失敗回傳 0。 */
    fun parseIso(iso: String?): Long {
        if (iso.isNullOrBlank() || iso == "null") return 0
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }

    private val weekdays = arrayOf("", "週一", "週二", "週三", "週四", "週五", "週六", "週日")
    private val monthDayFmt = DateTimeFormatter.ofPattern("M/d", Locale.TAIWAN)

    /**
     * 重置時間 → 好懂的相對說法：
     * 「45 分鐘後重置」「3 小時 20 分後重置」「週六 15:00 重置」「11/5 重置」。
     * short=true 給小工具用，更精簡（省略分鐘）。
     */
    fun resetRelative(epochMs: Long, short: Boolean = false): String {
        if (epochMs <= 0) return ""
        val now = System.currentTimeMillis()
        val mins = (epochMs - now) / 60_000
        val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return when {
            mins <= 0 -> "即將重置"
            mins < 60 -> "$mins 分鐘後重置"
            mins < 24 * 60 -> {
                val h = mins / 60
                val m = mins % 60
                if (short || m == 0L) "$h 小時後重置" else "$h 小時 $m 分後重置"
            }
            mins < 7 * 24 * 60 -> "${weekdays[t.dayOfWeek.value]} ${timeFmt.format(t)} 重置"
            else -> "${monthDayFmt.format(t)} 重置"
        }
    }

    /** 金額精簡：整數不帶小數，例如 $20、$10.41 */
    fun usdShort(v: Double): String =
        if (v % 1.0 == 0.0) String.format(Locale.US, "$%,.0f", v) else usd(v)

    /** 容量精簡：8 GB、64 MB（整數時不帶小數） */
    fun bytesShort(v: Double): String = when {
        v >= 1e9 -> {
            val g = v / 1e9
            if (g >= 10 || g % 1.0 == 0.0) String.format(Locale.US, "%.0f GB", g) else String.format(Locale.US, "%.1f GB", g)
        }
        v >= 1e6 -> String.format(Locale.US, "%.0f MB", v / 1e6)
        else -> String.format(Locale.US, "%.0f KB", v / 1e3)
    }
}

/** 用量等級：0 = 正常（<70%）、1 = 注意（70–89%）、2 = 快用完（≥90%） */
fun usageLevel(percent: Double): Int = when {
    percent >= 90 -> 2
    percent >= 70 -> 1
    else -> 0
}
