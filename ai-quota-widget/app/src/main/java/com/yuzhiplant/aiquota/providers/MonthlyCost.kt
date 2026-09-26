package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.model.QuotaItem
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
}
