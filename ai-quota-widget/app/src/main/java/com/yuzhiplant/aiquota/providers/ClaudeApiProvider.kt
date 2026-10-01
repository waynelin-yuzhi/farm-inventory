package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Claude API（Console）本月花費，透過官方 Admin API 的 Cost Report。
 * 需要 Admin API key（sk-ant-admin...）。Anthropic 沒有提供「剩餘儲值額度」的 API，
 * 所以上限用使用者在設定裡自填的月預算來算百分比。
 */
object ClaudeApiProvider {
    const val ID = "claude_api"
    private const val NAME = "Claude API 花費"

    fun fetch(settings: Settings): ProviderResult? {
        val key = settings.anthropicAdminKey
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            val todayUtc = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
            val monthStart = todayUtc.withDayOfMonth(1)
            val monthStr = monthStart.toLocalDate().toString()
            // 有填儲值餘額時，也要從填入那天開始加總
            val balance = settings.apiBalance
            val anchorStr = settings.apiBalanceDate.takeIf { balance > 0 && it.length == 10 }
            val queryStart = listOfNotNull(monthStr, anchorStr).minOrNull()!!
                .let { java.time.LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC) }
            val headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "Accept" to "application/json",
            )

            var monthCents = 0.0
            var todayCents = 0.0
            var sinceAnchorCents = 0.0
            var anchorDayCents = 0.0
            val byModel = mutableMapOf<String, Double>()
            var page: String? = null
            var guard = 0
            do {
                val url = buildString {
                    append("https://api.anthropic.com/v1/organizations/cost_report")
                    // 從「月初前一天」開始查：每月 1 號時，API 會把結束時間算成今天 0 點，
                    // 若開始也是今天 0 點就會回 400（ending date must be after starting date）。
                    // 前一天的花費在下面加總時會排除，結果仍是「本月已花費」。
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
                        val inMonth = day >= monthStr
                        val sinceAnchor = anchorStr != null && day >= anchorStr
                        val isToday = bucket.optString("starting_at").startsWith(todayUtc.toLocalDate().toString())
                        val results = bucket.optJSONArray("results") ?: continue
                        for (j in 0 until results.length()) {
                            // amount 是以「美分」為單位的十進位字串
                            val r = results.getJSONObject(j)
                            val cents = r.optString("amount").toDoubleOrNull() ?: 0.0
                            if (sinceAnchor) sinceAnchorCents += cents
                            if (sinceAnchor && day == anchorStr) anchorDayCents += cents
                            if (!inMonth) continue
                            monthCents += cents
                            val model = r.optString("model").takeIf { it.isNotEmpty() && it != "null" } ?: "其他（工具、網搜等）"
                            byModel[model] = (byModel[model] ?: 0.0) + cents
                            if (isToday) todayCents += cents
                        }
                    }
                }
                page = if (json.optBoolean("has_more")) {
                    json.optString("next_page").takeIf { it.isNotEmpty() && it != "null" }
                } else {
                    null
                }
                guard++
            } while (page != null && guard < 10)

            val items = mutableListOf<QuotaItem>()
            if (anchorStr != null) {
                // 剛填入餘額：把填入當天已花的金額記成基準，之後只扣新增的
                if (settings.apiBalancePending) {
                    settings.apiBalanceBaselineCents = anchorDayCents
                    settings.apiBalancePending = false
                }
                items += balanceItem(balance, anchorStr, (sinceAnchorCents - settings.apiBalanceBaselineCents) / 100)
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

    /** 估計剩餘儲值：填入的餘額 − 之後的花費，並依平均每日花費推估還能用幾天 */
    private fun balanceItem(balance: Double, anchorStr: String, spentSince: Double): QuotaItem {
        val spent = spentSince.coerceAtLeast(0.0)
        val remaining = balance - spent
        val anchor = java.time.LocalDate.parse(anchorStr)
        val days = ChronoUnit.HOURS.between(anchor.atStartOfDay(ZoneOffset.UTC), ZonedDateTime.now(ZoneOffset.UTC)) / 24.0
        val perDay = if (days >= 1) spent / days else 0.0
        val daysLeft = if (perDay > 0 && remaining > 0) (remaining / perDay).toInt() else -1
        val hint = when {
            remaining <= 0 -> "可能已用完，請到 Console 確認"
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

    /** Anthropic 錯誤格式：{"type":"error","error":{"message":"…"}} */
    private fun apiMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.take(120)
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
