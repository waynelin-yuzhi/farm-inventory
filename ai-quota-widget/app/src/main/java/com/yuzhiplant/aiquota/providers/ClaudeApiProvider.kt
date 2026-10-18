package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Claude API（Console）本月花費，透過官方 Admin API 的 Cost Report。
 * 需要 Admin API key（sk-ant-admin...）。Anthropic 沒有提供「剩餘儲值額度」的 API，
 * 所以上限用使用者在設定裡自填的月預算來算百分比。
 * Max 方案每月贈送的 API 額度（5x $100、20x $200）會先於儲值使用、每期歸零，
 * 這裡依訂閱扣款日切週期，算出本期用了多少、超出多少（超出部分才扣儲值）。
 */
object ClaudeApiProvider {
    const val ID = "claude_api"
    private const val NAME = "Claude API 花費"

    fun fetch(settings: Settings): ProviderResult? {
        val key = settings.anthropicAdminKey
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            val today = LocalDate.now(ZoneOffset.UTC)
            val todayStr = today.toString()
            val monthStr = today.withDayOfMonth(1).toString()
            // 有填儲值餘額時，也要從填入那天開始加總
            val balance = settings.apiBalance
            val anchorStr = settings.apiBalanceDate.takeIf { balance > 0 && it.length == 10 }
            // Max 方案每月贈送的 API 額度：依訂閱扣款日為週期（未填扣款日時以每月 1 號計）
            val credit = settings.apiMonthlyCredit
            val resetDay = settings.apiCreditResetDay.takeIf { it in 1..31 } ?: 1
            val cycleStart = if (credit > 0) cycleStart(today, resetDay) else null
            // 餘額扣款要從「填入那天所在的贈送週期」開始算，才知道當時贈送額度用掉多少
            val anchorCycleStart = if (credit > 0 && anchorStr != null) cycleStart(LocalDate.parse(anchorStr), resetDay) else null
            val queryStart = listOfNotNull(monthStr, anchorStr, cycleStart?.toString(), anchorCycleStart?.toString()).minOrNull()!!
                .let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC) }
            val headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "Accept" to "application/json",
            )

            // 每日花費（UTC 日期 → 美分），之後各種區間都從這裡加總
            val daily = sortedMapOf<String, Double>()
            val byModel = mutableMapOf<String, Double>()
            var page: String? = null
            var guard = 0
            do {
                val url = buildString {
                    append("https://api.anthropic.com/v1/organizations/cost_report")
                    // 從「起始日前一天」開始查：每月 1 號時，API 會把結束時間算成今天 0 點，
                    // 若開始也是今天 0 點就會回 400（ending date must be after starting date）。
                    // 多查的那天在下面加總時不會落在任何區間內。
                    append("?starting_at=").append(enc(DateTimeFormatter.ISO_INSTANT.format(queryStart.minusDays(1))))
                    append("&bucket_width=1d&limit=31&group_by%5B%5D=description")
                    if (page != null) append("&page=").append(enc(page!!))
                }
                val json = JSONObject(Http.get(url, headers))
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val bucket = data.getJSONObject(i)
                        // ISO 日期字串可直接比大小
                        val day = bucket.optString("starting_at").take(10)
                        val results = bucket.optJSONArray("results") ?: continue
                        for (j in 0 until results.length()) {
                            // amount 是以「美分」為單位的十進位字串
                            val r = results.getJSONObject(j)
                            val cents = r.optString("amount").toDoubleOrNull() ?: 0.0
                            daily[day] = (daily[day] ?: 0.0) + cents
                            if (day < monthStr) continue
                            val model = r.optString("model").takeIf { it.isNotEmpty() && it != "null" } ?: "其他（工具、網搜等）"
                            byModel[model] = (byModel[model] ?: 0.0) + cents
                        }
                    }
                }
                page = if (json.optBoolean("has_more")) {
                    json.optString("next_page").takeIf { it.isNotEmpty() && it != "null" }
                } else {
                    null
                }
                guard++
            } while (page != null && guard < 15)

            /** [from, until) 區間的花費（美分）；until 為 null 表示到今天 */
            fun sum(from: String, until: String? = null) =
                daily.filterKeys { it >= from && (until == null || it < until) }.values.sum()

            val monthCents = sum(monthStr)
            val todayCents = daily[todayStr] ?: 0.0
            val items = mutableListOf<QuotaItem>()
            if (cycleStart != null) {
                val next = nextCycleStart(cycleStart, resetDay)
                items += creditItems(credit, cycleStart, next, sum(cycleStart.toString()) / 100, settings.apiCreditResetDay in 1..31)
            }
            if (anchorStr != null) {
                // 剛填入餘額：把填入當天已花的金額記成基準，之後只扣新增的
                if (settings.apiBalancePending) {
                    settings.apiBalanceBaselineCents = daily[anchorStr] ?: 0.0
                    settings.apiBalancePending = false
                }
                val baseline = settings.apiBalanceBaselineCents
                val drawnCents = if (anchorCycleStart != null) {
                    // 贈送額度會先用：每個週期只有超出贈送額度的部分才從儲值扣
                    val creditCents = credit * 100
                    var drawn = 0.0
                    var cs: LocalDate = anchorCycleStart
                    while (!cs.isAfter(today)) {
                        val ce = nextCycleStart(cs, resetDay)
                        val total = sum(cs.toString(), ce.toString())
                        val before = if (cs == anchorCycleStart) sum(cs.toString(), anchorStr) + baseline else 0.0
                        drawn += (total - creditCents).coerceAtLeast(0.0) - (before - creditCents).coerceAtLeast(0.0)
                        cs = ce
                    }
                    drawn
                } else {
                    sum(anchorStr) - baseline
                }
                items += MonthlyCost.balanceItem(balance, anchorStr, drawnCents / 100, "Console")
            }
            val spent = monthCents / 100
            items += MonthlyCost.items(spent, settings.apiMonthlyBudget)
            items += QuotaItem("今日花費（UTC）", null, Format.usd(todayCents / 100), showInWidget = false)
            // 各模型本月花費（前 4 名），只在 App 內顯示
            byModel.entries.sortedByDescending { it.value }.take(4).filter { it.value > 0 }.forEach { (model, cents) ->
                val share = if (monthCents > 0) cents / monthCents * 100 else 0.0
                items += QuotaItem("・$model", null, "${Format.usd(cents / 100)}（${Format.percent(share)}）", showInWidget = false)
            }
            ProviderResult(ID, NAME, items, null, now)
        } catch (e: Http.HttpException) {
            val msg = when (e.code) {
                401 -> "Admin key 無效"
                403 -> "此 key 沒有權限，需使用 Admin API key"
                else -> "連線錯誤（HTTP ${e.code}）" + (apiMessage(e.body)?.let { "：$it" } ?: "")
            }
            ProviderResult(ID, NAME, emptyList(), msg, now, authError = e.code == 401 || e.code == 403)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /** 包含 [date] 的贈送週期起始日：最近一個「扣款日」（該月沒有那天時用月底） */
    private fun cycleStart(date: LocalDate, resetDay: Int): LocalDate {
        val thisMonth = date.withDayOfMonth(minOf(resetDay, date.lengthOfMonth()))
        if (!thisMonth.isAfter(date)) return thisMonth
        val prev = date.minusMonths(1)
        return prev.withDayOfMonth(minOf(resetDay, prev.lengthOfMonth()))
    }

    private fun nextCycleStart(start: LocalDate, resetDay: Int): LocalDate {
        val m = start.plusMonths(1)
        return m.withDayOfMonth(minOf(resetDay, m.lengthOfMonth()))
    }

    /**
     * Max 每月贈送額度：本期已用 / 額度，下次重置日；
     * 依目前速度預估本期會超出時，多一行警示。
     */
    private fun creditItems(credit: Double, start: LocalDate, next: LocalDate, spent: Double, dayKnown: Boolean): List<QuotaItem> {
        val remaining = credit - spent
        val over = -remaining
        val resetAt = next.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hint = when {
            over > 0 -> "已超出 ${Format.usd(over)}，超出部分從儲值扣"
            !dayKnown -> "未填扣款日，暫以每月 1 號計"
            else -> ""
        }
        val detail = listOf(
            if (over > 0) "已用 ${Format.usd(spent)} / ${Format.usdShort(credit)}" else "剩 ${Format.usd(remaining)} / ${Format.usdShort(credit)}",
            "${start.monthValue}/${start.dayOfMonth} 起，未用完不保留",
            hint,
        ).filter { it.isNotEmpty() }.joinToString("・")
        val out = mutableListOf(
            QuotaItem(
                label = "Max 贈送額度",
                percent = spent / credit * 100,
                detail = detail,
                shortDetail = "${Format.usd(spent)} / ${Format.usdShort(credit)}",
                resetAt = resetAt,
                hint = hint,
            ),
        )
        // 依本期已過天數推估期末花費
        val elapsedDays = ChronoUnit.HOURS.between(start.atStartOfDay(ZoneOffset.UTC), ZonedDateTime.now(ZoneOffset.UTC)) / 24.0
        val cycleDays = ChronoUnit.DAYS.between(start, next).toDouble()
        if (over <= 0 && elapsedDays >= 1) {
            val projected = spent / elapsedDays * cycleDays
            if (projected > credit) {
                out += QuotaItem(
                    "⚠ 預估本期超出贈送額度",
                    null,
                    "照目前速度本期約 ${Format.usd(projected)}，會多扣儲值約 ${Format.usd(projected - credit)}",
                    shortDetail = "約多 ${Format.usd(projected - credit)}",
                )
            }
        }
        return out
    }

    /** Anthropic 錯誤格式：{"type":"error","error":{"message":"…"}} */
    private fun apiMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.take(120)
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
