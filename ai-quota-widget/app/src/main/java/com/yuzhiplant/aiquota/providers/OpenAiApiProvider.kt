package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * OpenAI API 本月花費，透過官方 Costs API（GET /v1/organization/costs）。
 * 需要 Admin key（sk-admin-...，組織 Owner 建立）。OpenAI 沒有「剩餘儲值」的 API，
 * 做法同 Claude API：使用者填入目前餘額，App 扣掉之後的花費。
 */
object OpenAiApiProvider {
    const val ID = "openai_api"
    private const val NAME = "OpenAI API 花費"

    fun fetch(settings: Settings): ProviderResult? {
        val key = settings.openaiAdminKey
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            val todayStr = LocalDate.now(ZoneOffset.UTC).toString()
            val monthStr = todayStr.take(8) + "01"
            val balance = settings.openaiBalance
            val anchorStr = settings.openaiBalanceDate.takeIf { balance > 0 && it.length == 10 }
            val queryStart = listOfNotNull(monthStr, anchorStr).minOrNull()!!
            val startSec = LocalDate.parse(queryStart).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")

            var month = 0.0
            var today = 0.0
            var sinceAnchor = 0.0
            var anchorDay = 0.0
            val byModel = mutableMapOf<String, Double>()
            var page: String? = null
            var guard = 0
            do {
                val url = buildString {
                    append("https://api.openai.com/v1/organization/costs")
                    append("?start_time=").append(startSec)
                    append("&bucket_width=1d&limit=180&group_by=line_item")
                    if (page != null) append("&page=").append(enc(page!!))
                }
                val json = JSONObject(Http.get(url, headers))
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val bucket = data.getJSONObject(i)
                        val day = Instant.ofEpochSecond(bucket.optLong("start_time")).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        val results = bucket.optJSONArray("results") ?: bucket.optJSONArray("result") ?: continue
                        for (j in 0 until results.length()) {
                            val r = results.getJSONObject(j)
                            val usd = r.optJSONObject("amount")?.optDouble("value", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
                            if (anchorStr != null && day >= anchorStr) sinceAnchor += usd
                            if (day == anchorStr) anchorDay += usd
                            if (day < monthStr) continue
                            month += usd
                            if (day == todayStr) today += usd
                            val model = modelOf(r.optString("line_item"))
                            byModel[model] = (byModel[model] ?: 0.0) + usd
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
                if (settings.openaiBalancePending) {
                    settings.openaiBalanceBaseline = anchorDay
                    settings.openaiBalancePending = false
                }
                items += MonthlyCost.balanceItem(balance, anchorStr, sinceAnchor - settings.openaiBalanceBaseline, "platform.openai.com")
            }
            items += MonthlyCost.items(month, settings.openaiMonthlyBudget)
            items += QuotaItem("今日花費（UTC）", null, Format.usd(today), showInWidget = false)
            byModel.entries.sortedByDescending { it.value }.take(4).filter { it.value > 0 }.forEach { (model, usd) ->
                val share = if (month > 0) usd / month * 100 else 0.0
                items += QuotaItem("・$model", null, "${Format.usd(usd)}（${Format.percent(share)}）", showInWidget = false)
            }
            ProviderResult(ID, NAME, items, null, now)
        } catch (e: Http.HttpException) {
            val msg = when (e.code) {
                401 -> "Admin key 無效"
                403 -> "此 key 沒有權限，需使用 Admin key（sk-admin 開頭）"
                else -> "連線錯誤（HTTP ${e.code}）" + (apiMessage(e.body)?.let { "：$it" } ?: "")
            }
            ProviderResult(ID, NAME, emptyList(), msg, now, authError = e.code == 401 || e.code == 403)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /** line_item 例如「gpt-4o-2024-08-06, input」，只取逗號前的模型名 */
    private fun modelOf(lineItem: String): String =
        lineItem.substringBefore(',').trim().takeIf { it.isNotEmpty() && it != "null" } ?: "其他"

    /** OpenAI 錯誤格式：{"error":{"message":"…"}} */
    private fun apiMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.take(120)
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
