package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.model.QuotaItem
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 本月花費的共用呈現：對比月預算的 %，並依目前速度預估月底金額。 */
object MonthlyCost {

    /** 以 UTC 計算本月已過天數比例，推估月底花費。 */
    fun forecast(spent: Double): Double {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val days = now.toLocalDate().lengthOfMonth()
        val elapsed = (now.dayOfMonth - 1) + (now.hour * 60 + now.minute) / 1440.0
        return if (elapsed < 1.0) spent else spent / elapsed * days
    }

    fun items(spent: Double, budget: Double): List<QuotaItem> {
        val projected = forecast(spent)
        val main = if (budget > 0) {
            QuotaItem(
                "本月花費",
                spent / budget * 100,
                "${Format.usd(spent)} / ${Format.usd(budget)}・預估月底 ${Format.usd(projected)}",
                shortDetail = "${Format.usd(spent)} / ${Format.usdShort(budget)}",
                hint = "預估月底 ${Format.usd(projected)}",
            )
        } else {
            QuotaItem("本月花費", null, "${Format.usd(spent)}（未設月預算）")
        }
        val out = mutableListOf(main)
        if (budget > 0 && projected > budget) {
            out += QuotaItem(
                "⚠ 預估月底超支",
                null,
                "約 ${Format.usd(projected)}，超出 ${Format.usd(projected - budget)}",
                shortDetail = "約 ${Format.usd(projected)}",
            )
        }
        return out
    }

    /**
     * 估計剩餘儲值：填入的餘額 − 之後的花費，並依平均每日花費推估還能用幾天。
     * [where] 是「可能已用完」時請使用者去確認的地方（例如 Console）。
     */
    fun balanceItem(balance: Double, anchorStr: String, spentSince: Double, where: String): QuotaItem {
        val spent = spentSince.coerceAtLeast(0.0)
        val remaining = balance - spent
        val anchor = LocalDate.parse(anchorStr)
        val days = ChronoUnit.HOURS.between(anchor.atStartOfDay(ZoneOffset.UTC), ZonedDateTime.now(ZoneOffset.UTC)) / 24.0
        val perDay = if (days >= 1) spent / days else 0.0
        val daysLeft = if (perDay > 0 && remaining > 0) (remaining / perDay).toInt() else -1
        val hint = when {
            remaining <= 0 -> "可能已用完，請到 $where 確認"
            daysLeft >= 0 -> "照目前速度約可再用 $daysLeft 天"
            else -> ""
        }
        val since = "${anchor.monthValue}/${anchor.dayOfMonth} 起"
        return QuotaItem(
            label = "估計剩餘儲值",
            percent = (spent / balance * 100).coerceAtMost(100.0),
            detail = listOf("剩 ${Format.usd(remaining.coerceAtLeast(0.0))} / ${Format.usdShort(balance)}", "$since 已用 ${Format.usd(spent)}", hint)
                .filter { it.isNotEmpty() }.joinToString("・"),
            shortDetail = "剩 ${Format.usd(remaining.coerceAtLeast(0.0))} / ${Format.usdShort(balance)}",
            hint = hint,
        )
    }
}
