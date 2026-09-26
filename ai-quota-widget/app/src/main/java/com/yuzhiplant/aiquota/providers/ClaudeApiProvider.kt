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
            val headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "Accept" to "application/json",
            )

            var monthCents = 0.0
            var todayCents = 0.0
            val byModel = mutableMapOf<String, Double>()
            var page: String? = null
            var guard = 0
            do {
                val url = buildString {
                    append("https://api.anthropic.com/v1/organizations/cost_report")
                    append("?starting_at=").append(enc(DateTimeFormatter.ISO_INSTANT.format(monthStart)))
                    append("&bucket_width=1d&limit=31&group_by%5B%5D=description")
                    if (page != null) append("&page=").append(enc(page!!))
                }
                val json = JSONObject(Http.get(url, headers))
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val bucket = data.getJSONObject(i)
                        val isToday = bucket.optString("starting_at").startsWith(todayUtc.toLocalDate().toString())
                        val results = bucket.optJSONArray("results") ?: continue
                        for (j in 0 until results.length()) {
                            // amount 是以「美分」為單位的十進位字串
                            val r = results.getJSONObject(j)
                            val cents = r.optString("amount").toDoubleOrNull() ?: 0.0
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

            val spent = monthCents / 100
            val items = MonthlyCost.items(spent, settings.apiMonthlyBudget).toMutableList()
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
                else -> "連線錯誤（HTTP ${e.code}）"
            }
            ProviderResult(ID, NAME, emptyList(), msg, now, authError = e.code == 401 || e.code == 403)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
