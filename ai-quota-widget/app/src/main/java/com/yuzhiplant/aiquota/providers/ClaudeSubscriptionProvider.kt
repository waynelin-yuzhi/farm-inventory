package com.yuzhiplant.aiquota.providers

import android.content.Context
import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.data.WebViewFetcher
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Claude 訂閱方案（Pro / Max）用量：5 小時時段、本週各模型（含 Fable）使用百分比。
 *
 * 讀的是 claude.ai 網頁版「設定 → 用量」背後的同一支接口，以瀏覽器的 sessionKey cookie 驗證。
 * 這不是官方公開 API，未來格式可能改變，所以欄位採「凡是帶 utilization 的物件都顯示」的寬鬆解析。
 */
object ClaudeSubscriptionProvider {
    const val ID = "claude_subscription"
    private const val NAME = "Claude App 用量"
    private const val BASE = "https://claude.ai/api"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private val labels = mapOf(
        "five_hour" to "目前時段（5 小時）",
        "seven_day" to "本週・所有模型",
        "seven_day_opus" to "本週・Opus",
        "seven_day_sonnet" to "本週・Sonnet",
        "seven_day_fable" to "本週・Fable",
        "seven_day_oauth_apps" to "本週・外部應用",
        "extra_usage" to "額外用量（本月）",
    )

    private const val DEBUG_PREFS = "debug"
    private const val KEY_RAW = "claude_usage_raw"

    /** 最近一次的原始回應（只有用量數字與重置時間，不含金鑰），設定頁可複製給開發者除錯 */
    fun lastRaw(context: Context): String =
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getString(KEY_RAW, "") ?: ""

    /** claude.ai 回傳的錯誤（sessionKey 無效等），和連線層錯誤分開處理 */
    private class AuthException(message: String) : Exception(message)

    suspend fun fetch(context: Context, settings: Settings): ProviderResult? {
        val key = settings.claudeSessionKey
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            var org = settings.claudeOrgId
            if (org.isEmpty()) {
                org = pickOrg(JSONArray(get(context, "$BASE/organizations", key)))
                settings.claudeOrgId = org
            }
            val body = try {
                get(context, "$BASE/organizations/$org/usage", key)
            } catch (e: AuthException) {
                // 組織可能變了，下次重新偵測
                settings.claudeOrgId = ""
                throw e
            }
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RAW, body).apply()
            val items = parseUsage(JSONObject(body))
            if (items.isEmpty()) {
                ProviderResult(ID, NAME, emptyList(), "回傳內容沒有用量欄位，可能是免費帳號或格式已變更", now)
            } else {
                ProviderResult(ID, NAME, items, null, now)
            }
        } catch (e: AuthException) {
            ProviderResult(ID, NAME, emptyList(), "sessionKey 無效或已過期，請重新取得後貼上（${e.message}）", now, authError = true)
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 429) "查詢太頻繁，稍後再試" else "連線錯誤（HTTP ${e.code}）"
            ProviderResult(ID, NAME, emptyList(), msg, now)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /**
     * 先用一般 HTTP 連線；被 Cloudflare 擋下（403 且回傳 HTML）時，改用隱藏 WebView 取得。
     * 回傳內容若是 claude.ai 的錯誤 JSON，丟出 AuthException。
     */
    private suspend fun get(context: Context, url: String, key: String): String {
        val headers = mapOf(
            "Cookie" to "sessionKey=$key",
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "https://claude.ai/settings/usage",
            "Origin" to "https://claude.ai",
        )
        val body = try {
            withContext(Dispatchers.IO) { Http.get(url, headers) }
        } catch (e: Http.HttpException) {
            val looksJson = e.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            when {
                e.code == 429 -> throw e
                looksJson -> throw AuthException(errorMessage(e.body) ?: "HTTP ${e.code}")
                e.code == 401 || e.code == 403 || e.code == 503 ->
                    WebViewFetcher.fetch(context, url, "https://claude.ai", "sessionKey=$key")
                else -> throw e
            }
        }
        errorMessage(body)?.let { throw AuthException(it) }
        return body
    }

    /** claude.ai 錯誤格式：{"type":"error","error":{"type":"...","message":"..."}} */
    private fun errorMessage(body: String): String? = try {
        val o = JSONTokener(body).nextValue() as? JSONObject
        if (o != null && o.optString("type") == "error") {
            o.optJSONObject("error")?.optString("type")?.takeIf { it.isNotEmpty() } ?: "error"
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** 帳號可能同時屬於多個組織，優先挑有 Pro/Max 權限的那個。 */
    private fun pickOrg(arr: JSONArray): String {
        if (arr.length() == 0) throw IllegalStateException("找不到任何組織")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val caps = o.optJSONArray("capabilities")?.toString() ?: ""
            if (caps.contains("claude_max") || caps.contains("claude_pro")) return o.getString("uuid")
        }
        return arr.getJSONObject(0).getString("uuid")
    }

    /**
     * 解析用量回應。以 `limits` 清單為主（Claude App「Usage」頁面用的就是它，含各模型專屬的
     * 每週額度，例如 Fable）；舊欄位（five_hour / seven_day / 各代號）只補 `limits` 沒有的。
     */
    private fun parseUsage(json: JSONObject): List<QuotaItem> {
        val found = mutableListOf<Triple<Int, String, QuotaItem>>()
        val limits = json.optJSONArray("limits")
        val hasLimits = limits != null && limits.length() > 0
        if (hasLimits) {
            for (i in 0 until limits!!.length()) {
                val l = limits.optJSONObject(i) ?: continue
                if (l.isNull("percent")) continue
                val kind = l.optString("kind")
                val scopeName = l.optJSONObject("scope")?.let { sc ->
                    sc.optJSONObject("model")?.optString("display_name")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: sc.optJSONObject("surface")?.optString("display_name")?.takeIf { it.isNotBlank() && it != "null" }
                }
                val (rank, label) = when {
                    kind == "session" -> 0 to "目前時段（5 小時）"
                    kind == "weekly_all" -> 1 to "本週・所有模型"
                    scopeName != null -> 2 to "本週・$scopeName"
                    else -> 3 to kind.replace('_', ' ')
                }
                found += Triple(rank, label, QuotaItem(
                    label = label,
                    percent = l.optDouble("percent"),
                    detail = "",
                    resetAt = Format.parseIso(l.optString("resets_at")),
                ))
            }
        }
        // 舊欄位：有 limits 時跳過已涵蓋的 5 小時 / 本週
        for (key in json.keys()) {
            if (key == "limits" || key == "spend" || key == "seven_day_breakdown") continue
            if (hasLimits && (key == "five_hour" || key == "seven_day")) continue
            val o = json.optJSONObject(key) ?: continue
            itemFromLegacy(key, o)?.let { found += it }
        }
        breakdownItem(json.optJSONObject("seven_day_breakdown"))?.let { found += Triple(5, it.label, it) }
        return found
            .distinctBy { it.second }
            .sortedWith(compareBy<Triple<Int, String, QuotaItem>>({ it.first }, { it.second }))
            .map { it.third }
    }

    /** 舊格式的單一額度物件：{utilization, resets_at, limit_dollars, used_dollars, …} */
    private fun itemFromLegacy(key: String, o: JSONObject): Triple<Int, String, QuotaItem>? {
        if (o.isNull("utilization")) return null
        val util = o.optDouble("utilization", Double.NaN)
        if (util.isNaN()) return null
        val resetAt = Format.parseIso(o.optString("resets_at"))
        val limitDollars = if (o.isNull("limit_dollars")) Double.NaN else o.optDouble("limit_dollars", Double.NaN)
        // 0%、沒有重置時間、也沒有金額上限的，是沒在用的額度，略過
        if (util == 0.0 && resetAt == 0L && limitDollars.isNaN()) return null

        var money = ""
        if (!limitDollars.isNaN() && limitDollars > 0) {
            val used = o.optDouble("used_dollars", 0.0)
            money = "${Format.usd(used)} / ${Format.usdShort(limitDollars)}"
        } else if (o.has("used_credits") && o.has("monthly_limit") && !o.isNull("monthly_limit")) {
            money = "${Format.usdShort(o.optDouble("used_credits") / 100)} / ${Format.usdShort(o.optDouble("monthly_limit") / 100)}"
        }
        val label = when {
            key in labels -> labels.getValue(key)
            key.contains("fable") -> "本週・Fable"
            key.startsWith("seven_day_") -> labelFor(key)
            !limitDollars.isNaN() -> "額度 ${Format.usdShort(limitDollars)}"
            else -> labelFor(key)
        }
        // 認得的額度才上小工具；看不懂的內部代號只在 App 內列出
        val known = key in labels || key.startsWith("seven_day_") || key.contains("fable")
        return Triple(if (known) 3 else 4, label, QuotaItem(
            label = label,
            percent = util,
            detail = money,
            showInWidget = known,
            shortDetail = money,
            resetAt = resetAt,
        ))
    }

    /** 本週用量來自哪裡（Claude Code / 對話 / Cowork…），只在 App 內顯示 */
    private fun breakdownItem(b: JSONObject?): QuotaItem? {
        val rows = b?.optJSONArray("rows") ?: return null
        val parts = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optDouble("percent", 0.0) > 0 }
            .sortedByDescending { it.optDouble("percent") }
            .map { "${it.optString("display_name")} ${Format.percent(it.optDouble("percent"))}" }
        if (parts.isEmpty()) return null
        return QuotaItem("本週用量來源", null, parts.joinToString("・"), showInWidget = false)
    }

    private fun labelFor(key: String): String = labels[key]
        ?: if (key.startsWith("seven_day_")) {
            "本週・" + key.removePrefix("seven_day_").replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        } else {
            "其他額度・" + key.replace('_', ' ')
        }
}
