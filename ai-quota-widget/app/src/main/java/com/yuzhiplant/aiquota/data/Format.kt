package com.yuzhiplant.aiquota.data

import java.text.DecimalFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN)
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

    /** ISO 時間字串 → 「重置 9/24 18:00」；解析失敗回傳空字串。 */
    fun resetsAt(iso: String?): String {
        if (iso.isNullOrBlank() || iso == "null") return ""
        return try {
            val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
            "重置 ${dateTimeFmt.format(t)}"
        } catch (e: Exception) {
            ""
        }
    }
}

/** 用量等級：0 = 正常（<70%）、1 = 注意（70–89%）、2 = 快用完（≥90%） */
fun usageLevel(percent: Double): Int = when {
    percent >= 90 -> 2
    percent >= 70 -> 1
    else -> 0
}
